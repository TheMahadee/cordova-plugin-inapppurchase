package com.alexdisler.inapppurchases;

import android.app.Activity;
import com.android.billingclient.api.*;
import java.util.*;

public class BillingManager implements PurchasesUpdatedListener {
  public interface Listener {
    void onReady();
    void onProductDetails(List<ProductDetails> details);
    void onPurchaseUpdated(List<Purchase> purchases, BillingResult result);
    void onError(String where, BillingResult result);
  }

  private final BillingClient client;
  private final Activity activity;
  private final Listener listener;
  private boolean ready = false;

  public BillingManager(Activity activity, Listener listener) {
    this.activity = activity;
    this.listener = listener;
    this.client = BillingClient.newBuilder(activity)
      .enablePendingPurchases()
      .setListener(this)
      .build();
  }

  public void start() {
    client.startConnection(new BillingClientStateListener() {
      @Override public void onBillingSetupFinished(BillingResult r) {
        if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) {
          ready = true; listener.onReady();
        } else listener.onError("startConnection", r);
      }
      @Override public void onBillingServiceDisconnected() { ready = false; }
    });
  }

  public boolean isReady() { return ready; }

  public void queryProducts(List<String> ids, String type) {
    if (!ready) return;
    List<QueryProductDetailsParams.Product> list = new ArrayList<>();
    for (String id : ids) {
      list.add(QueryProductDetailsParams.Product.newBuilder()
        .setProductId(id).setProductType(type).build());
    }
    QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
      .setProductList(list).build();
    client.queryProductDetailsAsync(params, (res, details) -> {
      if (res.getResponseCode() == BillingClient.BillingResponseCode.OK)
        listener.onProductDetails(details);
      else listener.onError("queryProductDetails", res);
    });
  }

  public BillingResult launch(ProductDetails pd, String offerTokenOrNull) {
    BillingFlowParams.ProductDetailsParams.Builder b =
      BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd);
    if (offerTokenOrNull != null) b.setOfferToken(offerTokenOrNull);
    BillingFlowParams params = BillingFlowParams.newBuilder()
      .setProductDetailsParamsList(Collections.singletonList(b.build())).build();
    return client.launchBillingFlow(activity, params);
  }

  public void acknowledgeIfNeeded(Purchase p) {
    if (!p.isAcknowledged()) {
      AcknowledgePurchaseParams a = AcknowledgePurchaseParams.newBuilder()
        .setPurchaseToken(p.getPurchaseToken()).build();
      client.acknowledgePurchase(a, r -> {});
    }
  }

  public void consume(String token, ConsumeResponseListener cb) {
    client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build(), cb);
  }

  public void queryOwned(String type, PurchasesResponseListener cb) {
    client.queryPurchasesAsync(
      QueryPurchasesParams.newBuilder().setProductType(type).build(), cb);
  }

  @Override public void onPurchasesUpdated(BillingResult r, List<Purchase> ps) {
    listener.onPurchaseUpdated(ps, r);
  }
}
