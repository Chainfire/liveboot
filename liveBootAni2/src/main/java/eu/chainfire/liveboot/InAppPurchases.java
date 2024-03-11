/* Copyright (C) 2011-2024 Jorrit "Chainfire" Jongma
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package eu.chainfire.liveboot;

import android.app.Activity;
import android.content.Context;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import eu.chainfire.librootjava.Logger;


@SuppressWarnings({"unused", "UnusedReturnValue", "RedundantSuppression", "FieldCanBeLocal"})
public class InAppPurchases implements Closeable, PurchasesUpdatedListener {
    public interface OnPurchaseListener {
        void onPurchase(Order order, InAppPurchase iap);
    }

    public static final String PURCHASE_KEY = "purchase.2";

    private static final String[] skuOnce = new String[] {
            PURCHASE_KEY
    };

    private static final String[] skuSub = new String[] {
    };

    public enum InAppPurchaseType { ONCE, SUBSCRIPTION }
    public enum OrderState { PURCHASED, CANCELED, REFUNDED }

    public static class InAppPurchase {
        private final String productId;
        private final InAppPurchaseType type;
        private final String price;
        private final long priceMicros;
        private final String priceCurrency;
        private final String title;
        private final String description;
        private final SkuDetails skuDetails;

        public InAppPurchase(SkuDetails skuDetails) throws JSONException {
            this.skuDetails = skuDetails;
            JSONObject j = new JSONObject(skuDetails.getOriginalJson());
            productId = j.getString("productId");
            type = j.getString("type").equals("subs") ? InAppPurchaseType.SUBSCRIPTION : InAppPurchaseType.ONCE;
            price = j.getString("price");
            priceMicros = j.getLong("price_amount_micros");
            priceCurrency = j.getString("price_currency_code");
            title = j.getString("title");
            description = j.getString("description");

            Logger.dp("IAP", "InAppPurchase: productId[%s] type[%s] price[%s] priceMicros[%d] priceCurrency[%s] title[%s] description[%s]", productId, type, price, priceMicros, priceCurrency, title, description);
        }

        public String getProductId() { return productId; }
        public InAppPurchaseType getType() { return type; }
        public String getPrice() { return price; }
        public long getPriceMicros() { return priceMicros; }
        public String getPriceCurrency() { return priceCurrency; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public SkuDetails getSkuDetails() { return skuDetails; }
    }

    public static class Order {
        private final String orderId;
        private final String packageName;
        private final String productId;
        private final long purchaseTime;
        private final OrderState purchaseState;
        private final String developerPayload;
        private final String purchaseToken;

        public Order(String sku, String json) throws JSONException {
            JSONObject j = new JSONObject(json);
            orderId = j.getString("orderId");
            packageName = j.getString("packageName");
            productId = sku;
            purchaseTime = j.getLong("purchaseTime");

            int state = j.getInt("purchaseState");
            switch (state) {
                case 0: purchaseState = OrderState.PURCHASED; break;
                case 2: purchaseState = OrderState.REFUNDED; break;
                case 1:
                default: purchaseState = OrderState.CANCELED; break;
            }

            String developerPayload = null;
            try {
                developerPayload = j.getString("developerPayload");
            } catch (JSONException ignored) {
            }
            this.developerPayload = developerPayload;

            purchaseToken = j.getString("purchaseToken");

            Logger.dp("IAP", "Order: orderId[%s] productId[%s] purchaseState[%s]", orderId, productId, purchaseState);
        }

        public String getOrderId() { return orderId; }
        public String getPackageName() { return packageName; }
        public String getProductId() { return productId; }
        public long getPurchaseTime() { return purchaseTime; }
        public OrderState getPurchaseState() { return purchaseState; }
        public String getDeveloperPayload() { return developerPayload; }
        public String getPurchaseToken() { return purchaseToken; }
    }

    private final Context context;
    private final boolean billingServiceFound;

    private final ArrayList<InAppPurchase> iaps = new ArrayList<>();
    private final ArrayList<Order> orders = new ArrayList<>();

    private volatile BillingClient billingClient = null;

    private volatile OnPurchaseListener onPurchaseListener = null;

    private final Object inflightSync = new Object();
    private volatile int inflight = 0;

    private final BillingClientStateListener billingClientStateListener = new BillingClientStateListener() {
        @Override
        public void onBillingServiceDisconnected() {
            billingClient = null;
            synchronized (inflightSync) {
                inflight = 0;
            }
        }

        @Override
        public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
            try {
                update();
            } finally {
                synchronized (inflightSync) {
                    if (inflight > 0) inflight--;
                }
            }
        }
    };

    public InAppPurchases(Context context) {
        this.context = context;
        boolean billingServiceFound = false;
        try {
            synchronized (inflightSync) {
                inflight++;
            }
            billingClient = BillingClient.
                    newBuilder(context).
                    setListener(this).
                    enablePendingPurchases().
                    build();
            billingClient.startConnection(billingClientStateListener);
            billingServiceFound = true;  //TODO test on non-GooglePlay device?
        } catch (Exception ignored) {
        }
        this.billingServiceFound = billingServiceFound;
    }

    @Override
    public void close() {
        try {
            if (billingClient != null) {
                billingClient.endConnection();
                billingClient = null;
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult responseCode, @Nullable List<Purchase> purchases) {
        Logger.dp("IAP", "onPurchasesUpdated");
        update();
        int count = 0;
        while (!isReady()) {
            count++;
            if (count == 100) break;
            try {
                Thread.sleep(64);
            } catch (Exception ignored) {
            }
        }
        if (onPurchaseListener != null) {
            if (purchases != null && purchases.size() > 0) {
                for (Purchase purchase : purchases) {
                    for (String sku : purchase.getSkus()) {
                        for (Order order : orders) {
                            if (order.getProductId().equals(sku)) {
                                for (InAppPurchase iap : iaps) {
                                    if (iap.getProductId().equals(sku)) {
                                        if (order.purchaseState == OrderState.PURCHASED) {
                                            onPurchaseListener.onPurchase(order, iap);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            onPurchaseListener = null;
        }
    }

    public boolean haveService() {
        return billingServiceFound;
    }

    public boolean isServiceConnected() {
        return (billingClient != null);
    }

    public boolean update() {
        for (int i = 0; i < 2; i++) {
            List<String> skuList = new ArrayList<>();
            Collections.addAll(skuList, i == 0 ? skuOnce : skuSub);
            SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();
            params.setSkusList(skuList);
            final String skuType = i == 0 ? BillingClient.SkuType.INAPP : BillingClient.SkuType.SUBS;
            params.setType(skuType);
            synchronized (inflightSync) {
                inflight++;
            }
            billingClient.querySkuDetailsAsync(params.build(), new SkuDetailsResponseListener() {
                @Override
                public void onSkuDetailsResponse(@NonNull BillingResult billingResult, @Nullable List<SkuDetails> list) {
                    try {
                        synchronized (InAppPurchases.this.iaps) {
                            ArrayList<InAppPurchase> iaps = new ArrayList<>();
                            InAppPurchaseType typeToCopy = skuType.equals(BillingClient.SkuType.INAPP) ? InAppPurchaseType.SUBSCRIPTION : InAppPurchaseType.ONCE;
                            for (InAppPurchase iap : InAppPurchases.this.iaps) {
                                if (iap.type.equals(typeToCopy)) {
                                    iaps.add(iap);
                                }
                            }

                            if (list != null) {
                                for (SkuDetails skuDetails : list) {
                                    boolean found = false;
                                    for (InAppPurchase iap : InAppPurchases.this.iaps) {
                                        if (skuDetails.getSku().equals(iap.getProductId())) {
                                            iaps.add(iap);
                                            found = true;
                                            break;
                                        }
                                    }
                                    if (!found) {
                                        try {
                                            iaps.add(new InAppPurchase(skuDetails));
                                        } catch (JSONException ignored) {
                                        }
                                    }
                                }
                            }

                            InAppPurchases.this.iaps.clear();
                            InAppPurchases.this.iaps.addAll(iaps);
                        }
                    } finally {
                        synchronized (inflightSync) {
                            if (inflight > 0) inflight--;
                        }
                    }
                }
            });

            synchronized (inflightSync) {
                inflight++;
            }
            billingClient.queryPurchasesAsync(skuType, new PurchasesResponseListener() {
                @Override
                public void onQueryPurchasesResponse(@NonNull BillingResult billingResult, @NonNull List<Purchase> list) {
                    try {
                        if (list != null) {
                            synchronized (InAppPurchases.this.orders) {
                                ArrayList<Order> remove = new ArrayList<>();
                                for (Purchase purchase : list) {
                                    for (Order order : InAppPurchases.this.orders) {
                                        for (String sku : purchase.getSkus()) {
                                            if (order.getProductId().equals(sku)) {
                                                remove.add(order);
                                            }
                                        }
                                    }
                                }
                                for (Order order : remove) {
                                    InAppPurchases.this.orders.remove(order);
                                }
                                for (Purchase purchase : list) {
                                    for (String sku : purchase.getSkus()) {
                                        try {
                                            InAppPurchases.this.orders.add(new Order(sku, purchase.getOriginalJson()));
                                        } catch (JSONException ignored) {
                                        }
                                    }
                                    if (!purchase.isAcknowledged()) {
                                        acknowledge(purchase.getPurchaseToken());
                                    }
                                }
                            }
                        }
                    } finally {
                        synchronized (inflightSync) {
                            if (inflight > 0) inflight--;
                        }
                    }
                }
            });
        }
        return true;
    }

    public InAppPurchase getInAppPurchase(String productId) {
        for (InAppPurchase iap : iaps) {
            if (iap.getProductId().equals(productId)) {
                return iap;
            }
        }
        return null;
    }

    public InAppPurchase[] getInAppPurchases() {
        InAppPurchase[] ret = iaps.toArray(new InAppPurchase[0]);
        Arrays.sort(ret, new Comparator<InAppPurchase>() {
            @Override
            public int compare(InAppPurchase lhs, InAppPurchase rhs) {
                if (lhs == rhs) return 0;
                if (lhs == null) return -1;
                if (rhs == null) return 1;
                if (lhs.equals(rhs)) return 0;

                if (lhs.type == rhs.type) {
                    return Long.compare(lhs.priceMicros, rhs.priceMicros);
                } else if (lhs.type == InAppPurchaseType.ONCE) {
                    return -1;
                } else {
                    return 1;
                }
            }
        });
        return ret;
    }

    public Order[] getOrders(String productId, boolean returnInactive) {
        ArrayList<Order> ret = new ArrayList<>();
        for (Order order : orders) {
            if ((productId == null) || order.getProductId().equals(productId)) {
                if (returnInactive || order.getPurchaseState().equals(OrderState.PURCHASED)) {
                    ret.add(order);
                }
            }
        }
        return ret.toArray(new Order[0]);
    }

    public boolean purchase(InAppPurchase iap, Activity activity, OnPurchaseListener onPurchaseListener) {
        try {
            this.onPurchaseListener = onPurchaseListener;
            BillingFlowParams purchaseParams = BillingFlowParams.newBuilder().
                    setSkuDetails(iap.getSkuDetails()).
                    build();
            billingClient.launchBillingFlow(activity, purchaseParams);
            return true;
        } catch (Exception e) {
            this.onPurchaseListener = null;
            Logger.ex(e);
            return false;
        }
    }

    public void acknowledge(Order order) {
        acknowledge(order.getPurchaseToken());
    }

    public void acknowledge(String purchaseToken) {
        try {
            Logger.dp("IAP", "Acknowledging [%s]", purchaseToken);
            AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder().
                    setPurchaseToken(purchaseToken).
                    build();
            billingClient.acknowledgePurchase(params, new AcknowledgePurchaseResponseListener() {
                @Override
                public void onAcknowledgePurchaseResponse(@NonNull BillingResult billingResult) {
                    Logger.dp("IAP", "Acknowledge response [%d]", billingResult.getResponseCode());
                }
            });
        } catch (Exception e) {
            Logger.ex(e);
        }
    }

    public void consume(Order order) {
        consume(order.getPurchaseToken());
    }

    public void consume(String purchaseToken) {
        try {
            Logger.dp("IAP", "Consuming [%s]", purchaseToken);
            ConsumeParams consumeParams = ConsumeParams.newBuilder().
                    setPurchaseToken(purchaseToken).
                    build();
            billingClient.consumeAsync(consumeParams, new ConsumeResponseListener() {
                @Override
                public void onConsumeResponse(@NonNull BillingResult billingResult, @NonNull String s) {
                    Logger.dp("IAP", "Consume response [%d]", billingResult.getResponseCode());
                }
            });
        } catch (Exception e) {
            Logger.ex(e);
        }
    }

    public boolean isReady() {
        synchronized (inflightSync) {
            return inflight == 0;
        }
    }
}
