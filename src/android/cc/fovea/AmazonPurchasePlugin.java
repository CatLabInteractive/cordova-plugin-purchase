/**
 * Cordova plugin for Amazon AppStore In-App Purchasing.
 *
 * Architecture:
 *
 *   All purchase data flows through a single persistent listener
 *   callback (setListener), matching the pattern used by the Google
 *   Play adapter.  Per-request callbacks (purchase, getPurchaseUpdates)
 *   signal success/failure only and never carry purchase data.
 *
 *   The listener uses setKeepCallback(true) so it remains active for
 *   the lifetime of the plugin.
 *
 *   When a purchase is initiated, the plugin starts polling
 *   getPurchaseUpdates(false) every 3 seconds until the purchase
 *   result arrives via onPurchaseResponse.  This is necessary because
 *   broadcast delivery from the Amazon Appstore is unreliable on
 *   Fire TV devices — the ResponseReceiver broadcast may be blocked
 *   by SELinux or never delivered.
 *
 *   On resume the plugin delays 500ms before calling
 *   getPurchaseUpdates(false) so the WebView has time to process any
 *   queued messages and the JavaScript bridge is ready to receive
 *   listener events.
 */

package cc.fovea;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.amazon.device.iap.PurchasingService;
import com.amazon.device.iap.PurchasingListener;
import com.amazon.device.iap.model.FulfillmentResult;
import com.amazon.device.iap.model.Product;
import com.amazon.device.iap.model.ProductDataResponse;
import com.amazon.device.iap.model.ProductType;
import com.amazon.device.iap.model.PurchaseResponse;
import com.amazon.device.iap.model.PurchaseUpdatesResponse;
import com.amazon.device.iap.model.Receipt;
import com.amazon.device.iap.model.UserData;
import com.amazon.device.iap.model.UserDataResponse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AmazonPurchasePlugin
        extends CordovaPlugin
        implements PurchasingListener {

    private static final String TAG = "CdvPurchase/Amazon";

    /** Delay (ms) before refreshing purchases on resume. */
    private static final long RESUME_DELAY_MS = 500;

    /** Interval (ms) for polling purchase updates while a purchase is in flight. */
    private static final long POLL_INTERVAL_MS = 3000;

    // ---- Callback contexts ------------------------------------------------

    /** Persistent listener – receives all purchase data. */
    private volatile CallbackContext mListenerContext;

    /** One-shot callback for the init request. */
    private volatile CallbackContext mInitCallback;

    /** One-shot callback for getProductData. */
    private volatile CallbackContext mGetProductDataCallback;

    /** One-shot callback for purchase – signals success/failure only. */
    private volatile CallbackContext mPurchaseCallback;

    /** One-shot callback for getPurchaseUpdates – signals success/failure only. */
    private volatile CallbackContext mGetPurchaseUpdatesCallback;

    /** Current Amazon user data (set after successful getUserData). */
    private volatile UserData mUserData;

    /** Whether the SDK has been initialized. */
    private volatile boolean mInitialized = false;

    /** Whether a purchase is currently in flight (waiting for result). */
    private volatile boolean mPurchaseInFlight = false;

    /** Pending purchases received while the listener may not be ready. */
    private final List<JSONObject> mPendingPurchases = new ArrayList<>();

    /** Handler for posting delayed work on the main thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** Runnable for periodic purchase polling. */
    private final Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (mInitialized) {
                Log.d(TAG, "poll — calling getPurchaseUpdates(false)");
                flushPendingPurchases();
                PurchasingService.getPurchaseUpdates(false);
            }
            // Re-schedule as long as a purchase is still in flight
            if (mPurchaseInFlight) {
                mHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    // ---- Cordova lifecycle ------------------------------------------------

    @Override
    public void initialize(final CordovaInterface cordova, final CordovaWebView webView) {
        super.initialize(cordova, webView);
        Log.d(TAG, "initialize()");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mPurchaseInFlight = false;
        mHandler.removeCallbacks(mPollRunnable);
    }

    /**
     * When the activity resumes, flush any pending purchases that were
     * received while the WebView was paused, then poll for purchase
     * updates after a short delay so the WebView has time to resume
     * and process queued messages.
     */
    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        if (mInitialized) {
            Log.d(TAG, "onResume — scheduling getPurchaseUpdates in " + RESUME_DELAY_MS + "ms");
            mHandler.postDelayed(() -> {
                Log.d(TAG, "onResume — flushing pending + calling getPurchaseUpdates(false)");
                flushPendingPurchases();
                PurchasingService.getPurchaseUpdates(false);
            }, RESUME_DELAY_MS);
        }
    }

    // ---- Cordova execute --------------------------------------------------

    @Override
    public boolean execute(final String action, final JSONArray args,
                           final CallbackContext callbackContext) throws JSONException {
        Log.d(TAG, "execute(" + action + ")");

        switch (action) {
            case "setListener":
                mListenerContext = callbackContext;
                keepCallback(callbackContext);
                // Flush any purchases that arrived before the listener was set.
                flushPendingPurchases();
                return true;

            case "init":
                mInitCallback = callbackContext;
                initAmazonIAP();
                return true;

            case "getProductData": {
                mGetProductDataCallback = callbackContext;
                JSONArray skusArray = args.getJSONArray(0);
                Set<String> skus = new HashSet<>();
                for (int i = 0; i < skusArray.length(); i++) {
                    skus.add(skusArray.getString(i));
                }
                PurchasingService.getProductData(skus);
                return true;
            }

            case "purchase": {
                mPurchaseCallback = callbackContext;
                mPurchaseInFlight = true;
                String productId = args.getString(0);
                PurchasingService.purchase(productId);
                // Start polling for purchase results in case the
                // broadcast / listener events are not delivered
                // (common on Fire TV).
                mHandler.postDelayed(mPollRunnable, POLL_INTERVAL_MS);
                return true;
            }

            case "notifyFulfillment": {
                String receiptId = args.getString(0);
                PurchasingService.notifyFulfillment(receiptId, FulfillmentResult.FULFILLED);
                callbackContext.success();
                return true;
            }

            case "getPurchaseUpdates":
                mGetPurchaseUpdatesCallback = callbackContext;
                PurchasingService.getPurchaseUpdates(false);
                return true;

            default:
                return false;
        }
    }

    // ---- SDK initialization -----------------------------------------------

    private void initAmazonIAP() {
        Log.d(TAG, "initAmazonIAP()");
        cordova.getActivity().runOnUiThread(() -> {
            try {
                PurchasingService.registerListener(
                        cordova.getActivity().getApplicationContext(),
                        AmazonPurchasePlugin.this);
                PurchasingService.getUserData();
                mInitialized = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize Amazon IAP: " + e.getMessage());
                CallbackContext cb = mInitCallback;
                if (cb != null) {
                    cb.error("Failed to initialize Amazon IAP: " + e.getMessage());
                    mInitCallback = null;
                }
            }
        });
    }

    // ---- PurchasingListener -----------------------------------------------

    @Override
    public void onUserDataResponse(final UserDataResponse response) {
        Log.d(TAG, "onUserDataResponse: " + response.getRequestStatus());
        CallbackContext cb = mInitCallback;

        switch (response.getRequestStatus()) {
            case SUCCESSFUL:
                mUserData = response.getUserData();
                Log.d(TAG, "User ID: " + mUserData.getUserId()
                        + ", Marketplace: " + mUserData.getMarketplace());
                if (cb != null) {
                    cb.success();
                    mInitCallback = null;
                }
                break;
            case FAILED:
            case NOT_SUPPORTED:
                Log.e(TAG, "onUserDataResponse failed: " + response.getRequestStatus());
                if (cb != null) {
                    cb.error("Failed to get user data: " + response.getRequestStatus());
                    mInitCallback = null;
                }
                break;
        }
    }

    @Override
    public void onProductDataResponse(final ProductDataResponse response) {
        Log.d(TAG, "onProductDataResponse: " + response.getRequestStatus());
        CallbackContext cb = mGetProductDataCallback;
        if (cb == null) {
            return;
        }

        switch (response.getRequestStatus()) {
            case SUCCESSFUL:
                try {
                    JSONObject result = new JSONObject();
                    JSONArray productsArray = new JSONArray();
                    for (Map.Entry<String, Product> entry : response.getProductData().entrySet()) {
                        Product product = entry.getValue();
                        JSONObject pj = new JSONObject();
                        pj.put("productId", product.getSku());
                        pj.put("title", product.getTitle());
                        pj.put("description", product.getDescription());
                        pj.put("price", product.getPrice());
                        pj.put("productType", product.getProductType().toString());
                        productsArray.put(pj);
                    }
                    result.put("products", productsArray);
                    JSONArray unavailable = new JSONArray();
                    for (String sku : response.getUnavailableSkus()) {
                        unavailable.put(sku);
                    }
                    result.put("unavailableSkus", unavailable);
                    cb.success(result);
                } catch (JSONException e) {
                    cb.error("Error parsing product data: " + e.getMessage());
                }
                mGetProductDataCallback = null;
                break;
            case FAILED:
            case NOT_SUPPORTED:
                cb.error("getProductData failed: " + response.getRequestStatus());
                mGetProductDataCallback = null;
                break;
        }
    }

    /**
     * Purchase result.  Data is delivered through the listener;
     * the per-request callback only signals success/failure.
     */
    @Override
    public void onPurchaseResponse(final PurchaseResponse response) {
        Log.d(TAG, "onPurchaseResponse: " + response.getRequestStatus());
        CallbackContext cb = mPurchaseCallback;

        // Purchase completed — stop polling.
        mPurchaseInFlight = false;
        mHandler.removeCallbacks(mPollRunnable);

        switch (response.getRequestStatus()) {
            case SUCCESSFUL:
                Receipt receipt = response.getReceipt();
                try {
                    JSONObject purchaseJson = receiptToJson(receipt);
                    JSONArray arr = new JSONArray();
                    arr.put(purchaseJson);
                    if (!emitToListener("purchasesUpdated", arr)) {
                        // Listener not ready; queue the purchase for later delivery.
                        synchronized (mPendingPurchases) {
                            mPendingPurchases.add(purchaseJson);
                        }
                        Log.d(TAG, "Purchase queued (listener not ready)");
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Error creating purchase JSON: " + e.getMessage());
                }
                if (cb != null) {
                    cb.success();
                    mPurchaseCallback = null;
                }
                break;
            case FAILED:
                if (cb != null) {
                    cb.error("Purchase failed");
                    mPurchaseCallback = null;
                }
                break;
            case INVALID_SKU:
                if (cb != null) {
                    cb.error("Invalid SKU");
                    mPurchaseCallback = null;
                }
                break;
            case ALREADY_PURCHASED:
                if (cb != null) {
                    cb.error("Already purchased");
                    mPurchaseCallback = null;
                }
                break;
            case NOT_SUPPORTED:
                if (cb != null) {
                    cb.error("Not supported");
                    mPurchaseCallback = null;
                }
                break;
        }
    }

    /**
     * Purchase-updates result (initial load & resume refresh).
     * Data is delivered through the listener; the per-request callback
     * only signals success/failure.
     */
    @Override
    public void onPurchaseUpdatesResponse(final PurchaseUpdatesResponse response) {
        Log.d(TAG, "onPurchaseUpdatesResponse: " + response.getRequestStatus());
        CallbackContext cb = mGetPurchaseUpdatesCallback;

        switch (response.getRequestStatus()) {
            case SUCCESSFUL:
                try {
                    JSONArray arr = new JSONArray();
                    for (Receipt r : response.getReceipts()) {
                        if (!r.isCanceled()) {
                            arr.put(receiptToJson(r));
                        }
                    }
                    emitToListener("setPurchases", arr);

                    if (response.hasMore()) {
                        PurchasingService.getPurchaseUpdates(false);
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing purchase updates: " + e.getMessage());
                }
                // Signal success (no data — data goes through listener)
                if (cb != null) {
                    cb.success();
                    mGetPurchaseUpdatesCallback = null;
                }
                break;
            case FAILED:
            case NOT_SUPPORTED:
                Log.e(TAG, "onPurchaseUpdatesResponse failed: " + response.getRequestStatus());
                if (cb != null) {
                    cb.error("getPurchaseUpdates failed: " + response.getRequestStatus());
                    mGetPurchaseUpdatesCallback = null;
                }
                break;
        }
    }

    // ---- Helpers -----------------------------------------------------------

    private JSONObject receiptToJson(final Receipt receipt) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("receiptId", receipt.getReceiptId());
        json.put("productId", receipt.getSku());
        json.put("productType", receipt.getProductType().toString());
        json.put("purchaseDate",
                receipt.getPurchaseDate() != null ? receipt.getPurchaseDate().getTime() : 0);
        json.put("canceled", receipt.isCanceled());
        UserData ud = mUserData;
        if (ud != null) {
            json.put("userId", ud.getUserId());
            json.put("marketplace", ud.getMarketplace());
        }
        return json;
    }

    /**
     * Emit purchase data through the persistent listener.
     *
     * @param type      "purchasesUpdated" or "setPurchases"
     * @param purchases JSON array of purchase objects
     * @return true if the message was sent, false if the listener is not set
     */
    private boolean emitToListener(final String type, final JSONArray purchases) {
        CallbackContext lc = mListenerContext;
        if (lc == null) {
            Log.w(TAG, "emitToListener(" + type + ") — listener not set, dropping");
            return false;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", type);
            JSONObject data = new JSONObject();
            data.put("purchases", purchases);
            msg.put("data", data);
            PluginResult pr = new PluginResult(PluginResult.Status.OK, msg);
            pr.setKeepCallback(true);
            lc.sendPluginResult(pr);
            Log.d(TAG, "emitToListener(" + type + ") — sent " + purchases.length() + " items");
            return true;
        } catch (JSONException e) {
            Log.e(TAG, "Error emitting " + type + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Flush pending purchases that arrived before the listener was ready.
     */
    private void flushPendingPurchases() {
        List<JSONObject> pending;
        synchronized (mPendingPurchases) {
            if (mPendingPurchases.isEmpty()) {
                return;
            }
            pending = new ArrayList<>(mPendingPurchases);
            mPendingPurchases.clear();
        }
        Log.d(TAG, "Flushing " + pending.size() + " pending purchase(s)");
        JSONArray arr = new JSONArray();
        for (JSONObject p : pending) {
            arr.put(p);
        }
        emitToListener("purchasesUpdated", arr);
    }

    /** Keep the callback context alive (for setListener). */
    private void keepCallback(final CallbackContext callbackContext) {
        PluginResult pr = new PluginResult(PluginResult.Status.NO_RESULT);
        pr.setKeepCallback(true);
        callbackContext.sendPluginResult(pr);
    }
}
