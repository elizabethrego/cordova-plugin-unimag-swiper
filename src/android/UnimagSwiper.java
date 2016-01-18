package com.wodify.cordova.plugin.unimagswiper;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import android.os.Build;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;

import IDTech.MSR.XMLManager.StructConfigParameters;
import IDTech.MSR.uniMag.uniMagReader;
import IDTech.MSR.uniMag.uniMagReaderMsg;
import IDTech.MSR.uniMag.uniMagReader.ReaderType;

/**
* This plugin utilizes a Broadcast Receivers to allow for swiping a credit or
* debit card and returning its parsed data for use in financial transactions.   
*/
public class UnimagSwiper extends CordovaPlugin implements uniMagReaderMsg {

    // Reference to application context for construction and resource purposes
    private Context context;

    // Broadcast receiver to detect headset events
    private final HeadsetReceiver headsetReceiver = new HeadsetReceiver();

    // Auto Config profile to use for connection on unsupported device
    private static StructConfigParameters profile = null;

    // Store name of file to retrieve and set Auto Config Profile
    private final static String PROFILE_PREFS = "AutoConfigProfile";

    // Reader from SDK to handle all swipe functionality
    private uniMagReader reader;

    // Type of uniMagReader for initialization
    private ReaderType readerType;

    // Indicates if the containing app has not manually deactivated reader
    private boolean readerActivated = false; 

    // Indicates if the SDK has called its connection callback
    private boolean readerConnected = false;

    // Stores user preference, default false
    private boolean enableLogs = false;

    // Indicates if Auto Config process is running
    private boolean autoConfigRunning = false;

    // Regex to parse raw card data
    private Pattern cardParserPtrn = null;


    /***************************************************
    * LIFECYCLE
    ***************************************************/


    /**
    * Called after plugin construction and fields have been initialized.
    */
    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {

        super.initialize(cordova, webView);

        context = this.cordova.getActivity().getApplicationContext();

        cardParserPtrn = Pattern.compile("%B(\\d+)\\^([^\\^]+)\\^(\\d{4})");

        loadAutoConfigProfile();
    }

    /**
    * Called when the system is about to start resuming a previous activity.
    * The reader is killed and Headset Receiver is unregistered.
    * 
    * @param multitasking
    *      Flag indicating if multitasking is turned on for app
    */
    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);

        if (readerActivated) {
            deactivateReader(null);
        }
    }

    /**
    * Called when the activity will start interacting with the user.
    * The reader is reinitialized as if the app was just opened.
    * 
    * @param multitasking
    *      Flag indicating if multitasking is turned on for app
    */
    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);

        if (readerActivated) {
            activateReader(null);
        }
    }


    /***************************************************
    * JAVASCRIPT INTERFACE IMPLEMENTATION
    ***************************************************/


    /**
    * Executes the request sent from JavaScript.
    *
    * @param action
    *      The action to execute.
    * @param args
    *      The exec() arguments in JSON form.
    * @param command
    *      The callback context used when calling back into JavaScript.
    * @return
    *      Whether the action was valid.
    */
    @Override
    public boolean execute(final String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
        if ("activateReader".equals(action)) {
            activateReader(callbackContext);
        } else if ("deactivateReader".equals(action)) {
            deactivateReader(callbackContext);
        } else if ("swipe".equals(action)) {
            swipe(callbackContext);
        } else if ("enableLogs".equals(action)) {
            if (args.length() > 0) {
                enableLogs(callbackContext, args.getBoolean(0));
            } else callbackContext.error("Boolean 'enable' not specified.");
        } else if ("setReaderType".equals(action)) {
            if (args.length() > 0) {
                setReaderType(callbackContext, args.getString(0));
            } else callbackContext.error("Reader type not specified.");
        } else if ("autoConfig".equals(action)) {
            autoConfig(callbackContext);
        } else {
            // Method not found.
            return false;
        }

        return true;
    }

    /**
    * Initializes registers the Headset Receiver for headset detection, and starts
    * listen to SDK events for connection, disconnection, swiping, etc.
    * 
    * @param callbackContext 
    *        Used when calling back into JavaScript
    */
    private void activateReader(final CallbackContext callbackContext) {
        String callbackContextMsg = null;

        // This intent detects when something is plugged in or removed from the headset.
        IntentFilter headsetFilter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);

        // Check if we're running on Android 5.0 or higher
        if (Build.VERSION.SDK_INT >= 21) {
            // AudioManager.ACTION_HEADSET_PLUG preferred to Intent.ACTION_HEADSET_PLUG
            // on Lollipop and above
            headsetFilter = new IntentFilter(AudioManager.ACTION_HEADSET_PLUG);
        } else headsetFilter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);

        try {
            context.registerReceiver(headsetReceiver, headsetFilter);

            if (callbackContext != null) {
                readerActivated = true;
            }
        } catch(IllegalArgumentException e){
            e.printStackTrace();
            // If we're not going to be able to detect via hardware whether
            // the swipe is plugged in, we can't continue.
            callbackContextMsg = "Failed to activate reader - " +
            "Unable to register headset receiver.";
        }

        sendCallback(callbackContext, callbackContextMsg);
    } 

    /**
    * Because this unregisters the Headset Receiver, headset events will no 
    * longer be detected after calling this, thus swiper will no longer 
    * connect until activateReader is called again by the containing app,
    * unless this is called by onPause.
    * 
    * @param callbackContext 
    *        Used when calling back into JavaScript
    */
    private void deactivateReader(final CallbackContext callbackContext) {
        try {
            context.unregisterReceiver(headsetReceiver);

            stopUnimagSwiper();

            if (callbackContext != null) {
                readerActivated = false;
            }
        } catch(IllegalArgumentException e){
            // The only reason we could not unregister the Headset 
            // Receiver is that it was not registered in the first 
            // place (i.e., reader is already deactivated). Thus, we
            // don't really care about this exception, our callback
            // will always be for success.
            e.printStackTrace();
        }

        sendCallback(callbackContext, null);
    }

    /**
    * Tells the SDK to begin expecting a swipe. From the moment this is
    * called, the user will have 30 seconds to swipe the card before a 
    * timeout error occurs.
    * 
    * @param callbackContext 
    *        Used when calling back into JavaScript
    */
    private void swipe(final CallbackContext callbackContext) {
        if (reader != null && !autoConfigRunning) {
            if (readerConnected == true) {
                cancelSwipe();
                if (reader.startSwipeCard()) {
                    // If we get this far, we can expect events for card
                    // processing and card data received if a card is 
                    // actually swiped, otherwise we can expect a timeout
                    // event.
                    callbackContext.success();
                } else {
                    // Unexpected error
                    callbackContext.error("Failed to start swipe.");
                }
            } else {
                // Expected behavior if a disconnection event has been 
                // fired or swiper has never been connected.
                callbackContext.error("Reader has been activated but is not connected.");
            }
        } else callbackContext.error("Reader must be activated before starting swipe.");
    }

    /**
    * Turns SDK logs on or off.
    * 
    * @param callbackContext 
    *        Used when calling back into JavaScript
    * @param enabled
    *        True if logs should print.
    */
    private void enableLogs(final CallbackContext callbackContext, boolean enabled) {
        // Store preference
        enableLogs = enabled;

        // Apply preference now if possible, otherwise it will be
        // applied when swiper is started
        if (reader != null && !autoConfigRunning) {
            reader.setVerboseLoggingEnable(enableLogs);
        }

        callbackContext.success("Logging " + (enableLogs ? "en" : "dis") + "abled.");
    }

    /**
    * Restarts swiper with specified reader type if valid.
    * Not necessary, but could help when troubleshooting.
    * 
    * @param callbackContext 
    *        Used when calling back into JavaScript
    * @param type
    *        Type of reader to set
    */
    private void setReaderType(final CallbackContext callbackContext, String type) {
        try {
            readerType = ReaderType.valueOf(type);

            // Apply type now if possible, otherwise it will be
            // applied when swiper is started.
            if (reader != null && !autoConfigRunning) {
                stopUnimagSwiper();
                startUnimagSwiper();
            }

            callbackContext.success("Reader type set as '" + readerType.name() + "'.");
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            callbackContext.error("Reader type '" + type + "' invalid.");
        }
    }

    /** 
     * Starts Auto Config process to find a profile that can be 
     * used for connection on an unsupported device.
     *
     * @param callbackContext 
     *        Used when calling back into JavaScript
     */
    private void autoConfig(final CallbackContext callbackContext) {
        if (reader == null) {
            startUnimagSwiper();
        }
        if (!autoConfigRunning) {
            cancelSwipe();

            String file = getXMLConfigFile();

            if (reader.startAutoConfig(file, true)) {
                autoConfigRunning = true;

                callbackContext.success();
            } else callbackContext.error("Failed to start Auto Config.");
        } else callbackContext.error("Auto Config is already running.");
    }

    /***************************************************
    * SDK CALLBACKS
    ***************************************************/


    /**
    * Receive messages from the SDK when the device is powering up.
    * Can result in a timeout rather than an actual connection.
    */
    @Override
    public void onReceiveMsgToConnect() {
        fireEvent("connecting");
    }

    /**
    * Receive messages from the SDK when the swiper is connected to
    * the device. Swipe cannot be performed until this has been called.
    */
    @Override
    public void onReceiveMsgConnected() {
        readerConnected = true;
        fireEvent("connected");
    }

    /**
    * Receive messages from the SDK when the swiper becomes disconnected
    * from the device. 
    */
    @Override
    public void onReceiveMsgDisconnected() {
        readerConnected = false;
        autoConfigRunning = false;
        fireEvent("disconnected"); 
    }

    /**
    * Receive messages from the SDK when powerup, card swipe mode, or 
    * auto config is timed out. However, we cannot distinguish between
    * the former two timeouts without utilizing a stopwatch, which is 
    * probably overkill considering the timeout message, but may 
    * eventually be implemented. Auto config timeouts will be handled
    * separately.
    * 
    * @param strTimeoutMsg 
    *        Message from the SDK
    */
    @Override
    public void onReceiveMsgTimeout(String strTimeoutMsg) {
        if (autoConfigRunning) {
            autoConfigRunning = false;
            fireEvent("autoconfig_error", strTimeoutMsg);
        } else fireEvent("timeout", strTimeoutMsg);
    }

    /**
    * Receive messages from the SDK as soon as it detects data coming 
    * from the swiper after startSwipeCard() API method is called.
    */
    @Override
    public void onReceiveMsgProcessingCardData() {
        fireEvent("swipe_processing");
    }

    /**
    * Receive messages from the SDK with raw swiped card data and parse it.
    * 
    * @param flagOfCardData 
    *        Indicates format of cardData
    * @param cardData
    *        Raw card data to be parsed
    */
    @Override
    public void onReceiveMsgCardData(byte flagOfCardData, byte[] cardData) {
        cancelSwipe();

        JSONObject card = parseCardData(new String(cardData));
        if (card != null) {
            fireEvent("swipe_success", card.toString());   
        } else fireEvent("swipe_error");
    }

    /**
    * Receive messages from the SDK upon failure loading the XML file.
    * @param index      
    *        Identifier for failure type.
    * @param strMessage
    *        Description of error.
    */
    @Override
    public void onReceiveMsgFailureInfo(int index, String strMessage) {
        // Possible errors:
        //      - This phone model is not supported by the SDK.
        //      - Wrong XML file name.
        //      - XML file does not exist.
        //      - Cannot initialize XML file.
        //      - Failed to read XML file.
        //      - Can't download XML file.
        //      - Failed to increase media volume.
        //          NOTE: This can occur after starting
        //          Auto Config.
        fireEvent("xml_error", strMessage);
    }

    /**
    * Grant permissions for the SDK to do certain tasks.
    * @param  nType
    *         Task type
    * @param  strMessage
    *         Task description
    * @return
    *         True if task is identifiable
    */
    @Override 
    public boolean getUserGrant(int nType, String strMessage) {
        switch (nType)
        {
            case uniMagReaderMsg.typeToPowerupUniMag:
                // Let SDK to start powering up UniMag when
                // reader is plugged into the headphone jack
            case uniMagReaderMsg.typeToUpdateXML:
                // Let SDK download the latest configuration
                // file (.XML) from the IDTech web server if
                // mobile device has not yet been configured
            case uniMagReaderMsg.typeToOverwriteXML:
                // Let SDK download the latest configuration
                // file and overwrite existing file in the 
                // local storage
            case uniMagReaderMsg.typeToReportToIdtech:
                // Let SDK report an issue to IDTech when a 
                // mobile phone is not supported
                return true;
            default:
                return false;
        }
    }

    /**
     * Receive messages from SDK when Auto Config has completed.
     * Note that just because Auto Config has completed does not mean it
     * was successful, i.e., the profile found may still not work.
     * @param profile 
     *        Profile found by Auto Config, used to connect with
     */
    @Override
    public void onReceiveMsgAutoConfigCompleted(StructConfigParameters profile) {
        autoConfigRunning = false;
        // Store profile locally
        this.profile = profile;
        
        // Store profile in SharedPrefences for persistence
        boolean storeSuccess = storeAutoConfigProfile(profile);

        if (storeSuccess) {
            fireEvent("autoconfig_completed");

            // Totally reset reader to apply profile
            deactivateReader(null);
            activateReader(null);
        } else fireEvent("autoconfig_error", "Failed to save profile.");
    }

    /***************************************************
    * UNUSED SDK CALLBACKS
    ***************************************************/


    @Override
    public void onReceiveMsgToSwipeCard() {}

    @Override
    public void onReceiveMsgCommandResult(int commandID, byte[] cmdReturn) {}

    @Override
    public void onReceiveMsgToCalibrateReader() {}

    @Override
    public void onReceiveMsgAutoConfigProgress(int progressValue) {}

    @Override
    public void onReceiveMsgAutoConfigProgress(int percent, double result, String profileName) {}

    @Override
    @Deprecated
    public void onReceiveMsgSDCardDFailed(String strMSRData) {}


    /***************************************************
    * UTILS
    ***************************************************/

    /**
    * Initializes uniMagReader object and configures its settings.
    */
    private void startUnimagSwiper() {
        // If there is an existing uniMagReader object, kill it.
        stopUnimagSwiper();

        // Init with type if possible
        if (readerType != null) {
            reader = new uniMagReader(this, context, readerType);
        } else reader = new uniMagReader(this, context);

        // Begin listening to SDK events.
        reader.registerListen();
        reader.setVerboseLoggingEnable(enableLogs);
        reader.setTimeoutOfSwipeCard(30); // seconds 

        if (profile == null) {
            // XML file is used by SDK to retrieve device-specific settings
            // for the swiper. It is stored within this plugin's resources
            // but may also be downloaded from the ID Tech web server.
            reader.setXMLFileNameWithPath(getXMLConfigFile());
            reader.loadingConfigurationXMLFile(false);
        } else {
            // Device is not supported and must use profile from Auto Config
            reader.connectWithProfile(profile);
        }
    }
    
    /** 
    * Releases uniMagReader object.
    */
    private void stopUnimagSwiper() {
        if (reader != null) {
            cancelSwipe();

            // Stop listening to SDK events
            reader.unregisterListen();
            reader.release();
            reader = null;

            // Mock disconnection event
            readerConnected = false;
            fireEvent("disconnected");
        }
    }

    /**
    * Cancels a swipe if currently in swipe mode.
    */
    private void cancelSwipe() {
        if(reader.isSwipeCardRunning()) {
            reader.stopSwipeCard();
        }
    }

    /**
    * Uses a regex to parse raw card data.
    * @param  data
    *         Raw card data
    * @return
    *         Parsed card data or null if invalid
    */
    private JSONObject parseCardData(String data) {
        Matcher mtchr = cardParserPtrn.matcher(data);

        String num = null;
        String[] name = new String[2];
        String exp = null;

        while (mtchr.find()) {
            num = mtchr.group(1);
            name = mtchr.group(2).split("/");
            exp = mtchr.group(3);
        } 

        if (num != null && name[0] != null && name[1] != null && exp != null) {
            try {
                JSONObject cardData = new JSONObject();
                cardData.put("card_number", num);
                cardData.put("expiry_month", exp.substring(2));
                cardData.put("expiry_year", exp.substring(0, 2));
                cardData.put("first_name", name[1].trim());
                cardData.put("last_name", name[0].trim());
                cardData.put("trimmedUnimagData", data.replaceAll("\\s",""));

                return cardData;
            } catch (JSONException e) {
                e.printStackTrace();
                return null;
            }
        } else return null;
    }

    /**
    * Find path to XML configuration file for swiper.
    * @return 
    *     File name with path.
    */
    private String getXMLConfigFile() {
        String fileName = "IDT_uniMagCfg.xml";

        try {
            int resId = context.getResources().getIdentifier("idt_unimagcfg","raw", 
                                                    context.getPackageName());
            InputStream in = context.getResources().openRawResource(resId);
            byte [] buffer = new byte[in.available()];
            in.read(buffer);
            in.close();

            context.deleteFile(fileName);

            FileOutputStream out = context.openFileOutput(fileName, Context.MODE_PRIVATE);
            out.write(buffer);
            out.close();

            File fileDir = context.getFilesDir();
            String fileNameWithPath = fileDir.getParent() + File.separator + 
                                        fileDir.getName() + File.separator + fileName;

            File xmlConfigFile = new File(fileNameWithPath);
            return xmlConfigFile.exists() ? fileNameWithPath : null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Store profile retrieved by Auto Config process in SharedPreferences so
     * it can be loaded each time app is opened.
     *
     * @param profile
     *        Profile from Auto Config
     * @return
     *         True if store was successful
     */
    private boolean storeAutoConfigProfile(StructConfigParameters profile) {
        // Check that profile is valid
        if (profile == null) {
            return false;
        }

        // Create or open SharedPreferences file
        SharedPreferences.Editor profileEditor = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE).edit();

        profileEditor.putInt("direction_output_wave", profile.getDirectionOutputWave());
        profileEditor.putInt("frequence_input", profile.getFrequenceInput());
        profileEditor.putInt("frequence_output", profile.getFrequenceOutput());
        profileEditor.putInt("record_buffer_size", profile.getRecordBufferSize());
        profileEditor.putInt("record_read_buffer_size", profile.getRecordReadBufferSize());
        profileEditor.putInt("wave_direction", profile.getWaveDirection());
        profileEditor.putInt("high_threshold", profile.gethighThreshold());
        profileEditor.putInt("low_threshold", profile.getlowThreshold());
        profileEditor.putInt("min", profile.getMin());
        profileEditor.putInt("max", profile.getMax());
        profileEditor.putInt("baud_rate", profile.getBaudRate());
        profileEditor.putInt("pre_amble_factor", profile.getPreAmbleFactor());
        profileEditor.putInt("shuttle_channel", profile.getShuttleChannel() & 0xff);
        profileEditor.putInt("force_headset_plug", profile.getForceHeadsetPlug());
        profileEditor.putInt("use_voice_recognition", profile.getUseVoiceRecognition());
        profileEditor.putInt("volume_level_adjust", profile.getVolumeLevelAdjust());

        return profileEditor.commit();
    }

    /**
     * Loads profile retrieved by Auto Config process into app via 
     * SharedPreferences.
     */
    private void loadAutoConfigProfile() {
        SharedPreferences profilePrefs = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE);

        if (profilePrefs.getInt("frequence_input", 0) != 0) {
            profile = new StructConfigParameters();

            profile.setDirectionOutputWave((short) profilePrefs.getInt("direction_output_wave", 0));
            profile.setFrequenceInput(profilePrefs.getInt("frequence_input", 0));
            profile.setFrequenceOutput(profilePrefs.getInt("frequence_output", 0));
            profile.setRecordBufferSize(profilePrefs.getInt("record_buffer_size", 0));
            profile.setRecordReadBufferSize(profilePrefs.getInt("record_read_buffer_size", 0));
            profile.setWaveDirection(profilePrefs.getInt("wave_direction", 0));
            profile.sethighThreshold((short) profilePrefs.getInt("high_threshold", 0));
            profile.setlowThreshold((short) profilePrefs.getInt("low_threshold", 0));
            profile.setMin((short) profilePrefs.getInt("min", 0));
            profile.setMax((short) profilePrefs.getInt("max", 0));
            profile.setBaudRate(profilePrefs.getInt("baud_rate", 0));
            profile.setPreAmbleFactor((short) profilePrefs.getInt("pre_amble_factor", 0));
            profile.setShuttleChannel((byte) profilePrefs.getInt("shuttle_channel", 0));
            profile.setForceHeadsetPlug((short) profilePrefs.getInt("force_headset_plug", 0));
            profile.setUseVoiceRecognition((short) profilePrefs.getInt("use_voice_recognition", 0));
            profile.setVolumeLevelAdjust((short) profilePrefs.getInt("volume_level_adjust", 0));
        }
    }

    /**
     * Perform either a success or error callback on given CallbackContext
     * depending on state of msg.
     * @param callbackContext
     *        Used when calling back into JavaScript
     * @param msg
     *        Error message, or null if success
     */
    private void sendCallback(CallbackContext callbackContext, String msg) {
        // callbackContext will only be null when caller called from
        // lifecycle methods (i.e., never from containing app).
        if (callbackContext != null) {
            if (msg == null) {
                callbackContext.success();
            } else callbackContext.error(msg);
        }
    }

    /**
    * Pass event to method overload.
    * 
    * @param event
    *        The event name
    */
    private void fireEvent(String event) {
        fireEvent(event, null);
    }

    /**
    * Format and send event to JavaScript side.
    * 
    * @param event
    *        The event name
    * @param data
    *        Details about the event
    */
    private void fireEvent(String event, String data) {
        String dataArg = data != null ? "','" + data + "" : "";

        String js = "cordova.plugins.unimag.swiper.fireEvent('" + 
                        event + dataArg + "');";

        webView.sendJavascript(js);
    }


    /***************************************************
    * HEADSET RECEIVER CLASS
    ***************************************************/


    /**
    * Broadcast Receiver implementation used to detect headset events.
    */
    private class HeadsetReceiver extends BroadcastReceiver
    {
        /**
        * Will be called upon app startup if the swiper is plugged in and 
        * whenever something is plugged into or unplugged from the headset jack.
        * @param context 
        *        The context in which the receiver is running
        * @param intent  
        *        The intent received
        */
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();

                // Headset plugged in or removed
                if (Intent.ACTION_HEADSET_PLUG.equals(action)) {
                    if (intent.getIntExtra("state", 0) == 1) {
                        // Swiper was plugged in
                        startUnimagSwiper();
                    } else {
                        // Swiper was unplugged
                        stopUnimagSwiper();
                    }
                }
            } catch(Exception e) {
                e.printStackTrace();
                stopUnimagSwiper();
            }
        }
    }
}