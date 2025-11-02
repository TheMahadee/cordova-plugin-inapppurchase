package com.alexdisler.inapppurchases;

import android.app.Activity;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;

import java.io.InputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class InAppBillingV3 extends CordovaPlugin {

  protected static final String TAG = "google.payments";

  // keep original error codes so JS doesn’t change
  public static final int OK = 0;
  public static final int INVALID_ARGUMENTS = -1;
  public static final int UNABLE_TO_INITIALIZE = -2;
  public static final int BILLING_NOT_INITIALIZED = -3;
  public static final int UNKNOWN_ERROR = -4;
  public static final int USER_CANCELLED = -5;
  public static final int BAD_RESPONSE_FROM_SERVER = -6;
  public static final int VERIFICATION_FAILED = -7; // kept for compatibility
  public static final int ITEM_UNAVAILABLE = -8;
  public static final int ITEM_ALREADY_OWNED = -9;
  public static final int ITEM_NOT_OWNED = -10;
  public static final int CONSUME_FAILED = -11;

  // legacy constants (kept, but not used directly by v7)
  public static final int PURCHASE_PURCHASED = 0;
  public static final int PURCHASE_CANCELLED = 1;
  public static final int PURCHASE_REFUNDED = 2;

  // v7 wrapper
  private BillingManager billing;

  // cache of INAPP products to make buy() easy
  private static class CachedProduct {
    ProductDetails pd;
  }
  private final Map<String, CachedProduct> productCache = new HashMap<>();

  // who to answer after a buy flow
  private CallbackContext pendingBuyCallback;

  private JSONObject manifestObject = null;

  private JSONObject getManifestContents() {
    if (manifestObject != null) return manifestObject;
    try {
      InputStream is = this.cordova.getActivity().getAssets().open("www/manifest.json");
      Scanner s = new Scanner(is).useDelimiter("\\A");
      String manifestString = s.hasNext() ? s.next() : "";
      Log.d(TAG, "manifest:" + manifestString);
      manifestObject = new JSONObject(manifestString);
    } catch (IOException | JSONException e) {
      Log.d(TAG, "Unable to read/parse manifest file: " + e);
      manifestObject = null;
    }
    return manifestObject;
  }

  protected String getBase64EncodedPublicKey() {
    JSONObject m = getManifestContents();
    return (m != null) ? m.optString("play_store_key", null) : null;
  }

  protected boolean shouldSkipPurchaseVerification() {
    JSONObject m = getManifestContents();
    return (m != null) && m.optBoolean("skip_purchase_verification", false);
  }

  // ---- Cordova lifecycle ----

  @Override
  public void initialize(CordovaInterface cordova, CordovaWebView webView) {
    super.initialize(cordova, webView);
    // nothing heavy here; actual connect happens in init()
  }

  @Override
  public boolean execute(String action, final JSONArray args, final CallbackContext cb) {
    Log.d(TAG, "execute: " + action);
    switch (action) {
      case "init":
        return init(args, cb);
      case "buy":
        return buy(args, cb);
      case "subscribe":
        // not supported in your app; keep signature for compatibility
        cb.error(makeError("Subscriptions not supported", INVALID_ARGUMENTS));
        return true;
      case "consumePurchase":
        return consumePurchase(args, cb);
      case "getSkuDetails":
        return getSkuDetails(args, cb);
      case "restorePurchases":
        return restorePurchases(args, cb);
      default:
        return false;
    }
  }

  // ---- Common error JSON (kept for compatibility) ----

  protected JSONObject makeError(String message) {
    return makeError(message, null, null, null);
  }

  protected JSONObject makeError(String message, Integer resultCode) {
    return makeError(message, resultCode, null, null);
  }

  protected JSONObject makeError(String message, Integer resultCode, String text, Integer response) {
    if (message != null) Log.d(TAG, "Error: " + message);
    JSONObject error = new JSONObject();
    try {
      if (resultCode != null) error.put("code", (int)resultCode);
      if (message != null) error.put("message", message);
      if (text != null) error.put("text", text);
      if (response != null) error.put("response", response);
    } catch (JSONException ignore) {}
    return error;
  }

  // ---- Actions (consumables only) ----

  private boolean init(final JSONArray args, final CallbackContext cb) {
    if (billing != null && billing.isReady()) {
      cb.success();
      return true;
    }

    final Activity activity = this.cordova.getActivity();
    billing = new BillingManager(activity, new BillingManager.Listener() {
      @Override public void onReady() {
        cordova.getActivity().runOnUiThread(cb::success);
      }
      @Override public void onProductDetails(List<ProductDetails> details) {
        // routed by getSkuDetails()
      }
      @Override public void onPurchaseUpdated(List<Purchase> purchases, BillingResult r) {
        handlePurchasesUpdated(purchases, r);
      }
      @Override public void onError(String where, BillingResult r) {
        cb.error(makeError(where, UNABLE_TO_INITIALIZE, null, r.getResponseCode()));
      }
    });
    billing.start();
    return true;
  }

  // getSkuDetails(skuArray) -> returns legacy details JSON
  private boolean getSkuDetails(final JSONArray args, final CallbackContext cb) {
    if (billing == null || !billing.isReady()) {
      cb.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED));
      return true;
    }

    final List<String> ids = new ArrayList<>();
    for (int i = 0; i < args.length(); i++) ids.add(args.optString(i));

    // ask INAPP only
    billing.queryProducts(ids, BillingClient.ProductType.INAPP);

    // We’ll receive results via the same listener — wire a one-shot converter
    // Simplest way: temporarily install a small forwarder by recreating the manager
    // (Alternatively, keep a state flag. Keeping this simple for clarity.)
    final Activity activity = this.cordova.getActivity();
    billing = new BillingManager(activity, new BillingManager.Listener() {
      @Override public void onReady() {} // already ready
      @Override public void onProductDetails(List<ProductDetails> details) {
        try {
          JSONArray out = new JSONArray();
          productCache.clear();

          for (ProductDetails pd : details) {
            ProductDetails.OneTimePurchaseOfferDetails one = pd.getOneTimePurchaseOfferDetails();
            if (one == null) continue; // skip non-INAPP

            // cache for buy()
            CachedProduct cp = new CachedProduct();
            cp.pd = pd;
            productCache.put(pd.getProductId(), cp);

            // legacy JSON keys
            JSONObject j = new JSONObject();
            j.put("productId", pd.getProductId());
            j.put("title", pd.getTitle());
            j.put("description", pd.getDescription());
            j.put("priceAsDecimal", JSONObject.NULL); // not provided by v7; keep null for compat
            j.put("price", one.getFormattedPrice());
            j.put("type", "inapp");
            j.put("currency", one.getPriceCurrencyCode());
            Long micros = one.getPriceAmountMicros(); // may be null on some devices
            if (micros != null) {
              // convert micros (e.g., 990000) → "0.99"
              j.put("priceAsDecimal", String.valueOf(micros / 1_000_000.0));
            } else {
              j.put("priceAsDecimal", JSONObject.NULL);
            }
            out.put(j);
          }
          cordova.getActivity().runOnUiThread(() -> cb.success(out));
        } catch (Exception e) {
          cb.error(makeError("PRODUCT_PARSE_ERROR", UNKNOWN_ERROR));
        }
      }
      @Override public void onPurchaseUpdated(List<Purchase> purchases, BillingResult r) {
        handlePurchasesUpdated(purchases, r);
      }
      @Override public void onError(String where, BillingResult r) {
        cb.error(makeError(where, UNKNOWN_ERROR, null, r.getResponseCode()));
      }
    });
    billing.start(); // reattach listener to deliver results here
    // trigger again
    billing.queryProducts(ids, BillingClient.ProductType.INAPP);
    return true;
  }

  private boolean buy(final JSONArray args, final CallbackContext cb) {
    if (billing == null || !billing.isReady()) {
      cb.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED));
      return true;
    }
    final String sku = args.optString(0, null);
    if (sku == null || sku.isEmpty()) {
      cb.error(makeError("Invalid SKU", INVALID_ARGUMENTS));
      return true;
    }
    CachedProduct cp = productCache.get(sku);
    if (cp == null || cp.pd == null) {
      cb.error(makeError("PRODUCT_NOT_LOADED", ITEM_UNAVAILABLE));
      return true;
    }
    pendingBuyCallback = cb;

    BillingResult r = billing.launch(cp.pd, null); // no offer token for INAPP
    if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) {
      pendingBuyCallback = null;
      cb.error(makeError("LAUNCH_FAILED", UNKNOWN_ERROR, null, r.getResponseCode()));
    }
    return true;
  }

  // consumePurchase(type, receipt, signature)
  // We only need the token; it's inside the receipt JSON.
  private boolean consumePurchase(final JSONArray args, final CallbackContext cb) {
    if (billing == null || !billing.isReady()) {
      cb.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED));
      return true;
    }
    final String receipt = args.optString(1, null);
    if (receipt == null) {
      cb.error(makeError("Unable to parse purchase token", INVALID_ARGUMENTS));
      return true;
    }
    String token = null;
    String productId = null;
    String orderId = null;
    try {
      JSONObject rec = new JSONObject(receipt);
      token = rec.optString("purchaseToken", null);
      orderId = rec.optString("orderId", null);
      // In v7, owned product IDs live under "products" array if present
      if (rec.has("productId")) productId = rec.optString("productId");
    } catch (JSONException ignore) {}
    if (token == null) {
      cb.error(makeError("Unrecognized purchase token", INVALID_ARGUMENTS));
      return true;
    }

    final String fToken = token;
    final String fProduct = productId;
    final String fOrder = orderId;

    billing.consume(fToken, (br, outToken) -> {
      if (br.getResponseCode() == BillingClient.BillingResponseCode.OK) {
        try {
          JSONObject res = new JSONObject();
          res.put("transactionId", fOrder != null ? fOrder : JSONObject.NULL);
          res.put("productId", fProduct != null ? fProduct : JSONObject.NULL);
          res.put("token", fToken);
          cordova.getActivity().runOnUiThread(() -> cb.success(res));
        } catch (JSONException e) {
          cordova.getActivity().runOnUiThread(() -> cb.error("Consume succeeded but success handler failed"));
        }
      } else {
        cordova.getActivity().runOnUiThread(() ->
          cb.error(makeError("Error consuming purchase", CONSUME_FAILED, null, br.getResponseCode())));
      }
    });
    return true;
  }

  private boolean restorePurchases(final JSONArray args, final CallbackContext cb) {
    if (billing == null || !billing.isReady()) {
      cb.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED));
      return true;
    }
    billing.queryOwned(BillingClient.ProductType.INAPP, (r, list) -> {
      if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) {
        cordova.getActivity().runOnUiThread(() ->
          cb.error(makeError("Error retrieving purchase details", UNKNOWN_ERROR, null, r.getResponseCode())));
        return;
      }
      try {
        JSONArray out = new JSONArray();
        for (Purchase p : list) {
          JSONObject o = new JSONObject();
          String pid = p.getProducts().isEmpty() ? "" : p.getProducts().get(0);
          o.put("orderId", p.getOrderId());
          o.put("packageName", cordova.getActivity().getPackageName());
          o.put("productId", pid);
          o.put("purchaseTime", p.getPurchaseTime());
          // keep same key name as legacy code; state not directly exposed, keep 0 for owned
          o.put("purchaseState", PURCHASE_PURCHASED);
          o.put("purchaseToken", p.getPurchaseToken());
          o.put("signature", p.getSignature()); // may be null in modern libs
          o.put("type", "inapp");
          o.put("receipt", p.getOriginalJson());
          out.put(o);
        }
        final JSONArray result = out;
        cordova.getActivity().runOnUiThread(() -> cb.success(result));
      } catch (Exception e) {
        cordova.getActivity().runOnUiThread(() -> cb.error(e.getMessage()));
      }
    });
    return true;
  }

  // v7 doesn’t require onActivityResult handling for purchases
  @Override
  public void onActivityResult(int requestCode, int resultCode, android.content.Intent intent) {
    super.onActivityResult(requestCode, resultCode, intent);
  }

  @Override
  public void onDestroy() {
    billing = null; // BillingClient disconnects automatically; safe to drop ref
    super.onDestroy();
  }
}
