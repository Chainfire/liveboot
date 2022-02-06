/* Copyright (C) 2011-2022 Jorrit "Chainfire" Jongma
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
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.vending.billing.IInAppBillingService;

import eu.chainfire.librootjava.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public class InAppPurchases implements Closeable {    
    public static final String PURCHASE_KEY = "purchase.2";
    
    private static final String[] skuOnce = new String[] {
            PURCHASE_KEY
    };
    
    private static final String[] skuSub = new String[] {
    };
    
    public enum InAppPurchaseType { ONCE, SUBSCRIPTION };
    public enum OrderState { PURCHASED, CANCELED, REFUNDED };
    
    public class InAppPurchase {
        private String productId;
        private InAppPurchaseType type;
        private String price;
        private long priceMicros;
        private String priceCurrency;
        private String title;
        private String description;
        
        public InAppPurchase(String json) throws JSONException {
            JSONObject j = new JSONObject(json);
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
    }    
    
    public class Order {
        private String orderId;
        private String packageName;
        private String productId;
        private long purchaseTime;
        private OrderState purchaseState;
        private String developerPayload;
        private String purchaseToken;
        
        public Order(String json) throws JSONException {
            JSONObject j = new JSONObject(json);
            orderId = j.getString("orderId");
            packageName = j.getString("packageName");
            productId = j.getString("productId");
            purchaseTime = j.getLong("purchaseTime");
            
            int state = j.getInt("purchaseState");
            switch (state) {
            case 0: purchaseState = OrderState.PURCHASED; break;
            case 1: purchaseState = OrderState.CANCELED; break;
            case 2: purchaseState = OrderState.REFUNDED; break;
            default: purchaseState = OrderState.CANCELED; break;            
            }
            
            developerPayload = j.getString("developerPayload");
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
    
    private final ArrayList<InAppPurchase> iaps = new ArrayList<InAppPurchase>();
    private final ArrayList<Order> orders = new ArrayList<Order>();
        
    private volatile IInAppBillingService billingService = null;

    private ServiceConnection billingServiceConn = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            billingService = null;
        }
        
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            billingService = IInAppBillingService.Stub.asInterface(service);
        }
    };      
            
    public InAppPurchases(Context context) {
        this.context = context;
        boolean billingServiceFound = false;
        try {
            Intent i = new Intent("com.android.vending.billing.InAppBillingService.BIND");
            i.setPackage("com.android.vending");
            billingServiceFound = context.bindService(i, billingServiceConn, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {             
        }           
        this.billingServiceFound = billingServiceFound;
    }

    @Override
    public void close() throws IOException {
        if (billingService != null) {
            try {
                context.unbindService(billingServiceConn);
            } catch (Exception e) {                
            }
            billingService = null;
        }        
    }
    
    public boolean haveService() {
        return billingServiceFound;
    }
    
    public boolean isServiceConnected() {
        return (billingService != null);
    }
    
    private Bundle skuBundleFromStringArray(String[] skus) {
        ArrayList<String> skuList = new ArrayList<String>();
        for (String sku : skus) skuList.add(sku);
        Bundle ret = new Bundle();
        ret.putStringArrayList("ITEM_ID_LIST", skuList);
        return ret;
    }

    public boolean update() {
        final ArrayList<InAppPurchase> iaps = new ArrayList<InAppPurchase>();
        final ArrayList<Order> orders = new ArrayList<Order>();
        
        try {
            for (Bundle skuDetails : new Bundle[] { 
                    billingService.getSkuDetails(3, context.getPackageName(), "inapp", skuBundleFromStringArray(skuOnce)),
                    billingService.getSkuDetails(3, context.getPackageName(), "subs", skuBundleFromStringArray(skuSub)),
            }) {
                if (skuDetails != null) {
                    ArrayList<String> data = skuDetails.getStringArrayList("DETAILS_LIST");
                    if (data != null) {
                        for (String details : data) {
                            iaps.add(new InAppPurchase(details));
                        }
                    }
                }
            }
            
            for (String type : new String[] { "inapp", "subs" }) {
                String continuationToken = null;
                while (true) {
                    Bundle items = billingService.getPurchases(3, context.getPackageName(), type, continuationToken);
                    if (items == null) break;
                    if (items.getInt("RESPONSE_CODE", -1) != 0) break;

                    ArrayList<String> data = items.getStringArrayList("INAPP_PURCHASE_DATA_LIST");
                    if (data == null) break;
                    
                    for (String details : data) {
                        orders.add(new Order(details));
                    }
                    
                    continuationToken = items.getString("INAPP_CONTINUATION_TOKEN");
                    if (continuationToken == null) break;
                }                
            }         
            
            this.iaps.clear();            
            for (InAppPurchase iap : iaps) {
                this.iaps.add(iap);
            }
            this.orders.clear();            
            for (Order order : orders) {
                this.orders.add(order);
            }
        } catch (Exception e) {
            Logger.ex(e);
            return false;
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
        InAppPurchase[] ret = iaps.toArray(new InAppPurchase[iaps.size()]);
        Arrays.sort(ret, new Comparator<InAppPurchase>() {
            @Override
            public int compare(InAppPurchase lhs, InAppPurchase rhs) {
                if (lhs == rhs) return 0;
                if (lhs == null) return -1;
                if (rhs == null) return 1;
                if (lhs.equals(rhs)) return 0;
                
                if (lhs.type == rhs.type) {
                    if (lhs.priceMicros == rhs.priceMicros) {
                        return 0;
                    } else if (lhs.priceMicros < rhs.priceMicros) {
                        return -1;
                    } else {
                        return 1;
                    }
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
        ArrayList<Order> ret = new ArrayList<Order>();
        for (Order order : orders) {
            if ((productId == null) || order.getProductId().equals(productId)) {
                if (returnInactive || order.getPurchaseState().equals(OrderState.PURCHASED)) {
                    ret.add(order);
                }
            }
        }
        return ret.toArray(new Order[ret.size()]);
    }
    
    public boolean purchase(InAppPurchase iap, Activity activity, int requestCode) {
        try {
            Bundle buyIntentBundle = billingService.getBuyIntent(3, context.getPackageName(), iap.productId, iap.getType() == InAppPurchaseType.ONCE ? "inapp" : "subs", "nuclearPayloadsGoBoom");
            if (buyIntentBundle != null) {
                int response = buyIntentBundle.getInt("RESPONSE_CODE");
                if (response == 0) {
                    PendingIntent pendingIntent = buyIntentBundle.getParcelable("BUY_INTENT");
                    activity.startIntentSenderForResult(pendingIntent.getIntentSender(), requestCode, new Intent(), Integer.valueOf(0), Integer.valueOf(0), Integer.valueOf(0));
                    return true;
                }                               
            }
        } catch (RemoteException e) {    
            Logger.ex(e);
        } catch (SendIntentException e) {
            Logger.ex(e);
        }
        return false;
    }
    
    public boolean consume(String productId) {
        if (productId == null) return false;
        try {
            int ret = billingService.consumePurchase(3, context.getPackageName(), String.format(Locale.ENGLISH, "%s:%s:%s", "inapp", context.getPackageName(), productId));
            if (ret != 0) {
                Logger.dp("IAP", "consumePurchase: " + ret);
            }
            return (ret == 0);
        } catch (RemoteException e) {
            Logger.ex(e);
        }
        return false;        
    }
    
    public boolean consume(Order order) {
        if (order == null) return false;
        try {
            int ret = billingService.consumePurchase(3, context.getPackageName(), order.getPurchaseToken());
            if (ret != 0) {
                Logger.dp("IAP", "consumePurchase: " + ret);                
            }
            return (ret == 0);
        } catch (RemoteException e) {
            Logger.ex(e);
        }
        return false;
    }
}
