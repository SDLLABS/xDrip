package com.eveningoutpost.dexdrip;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.nfc.NfcManager;
import android.nfc.Tag;
import android.nfc.tech.NfcV;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;

import com.eveningoutpost.dexdrip.ImportedLibraries.usbserial.util.HexDump;
import com.eveningoutpost.dexdrip.Models.GlucoseData;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.PredictionData;
import com.eveningoutpost.dexdrip.Models.ReadingData;
import com.eveningoutpost.dexdrip.utils.DexCollectionType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

// From LibreAlarm et al

public class NFCReaderX {

    private static final String TAG = "NFCReaderX";
    private static final boolean d = false; // global debug flag
    public static final int REQ_CODE_NFC_TAG_FOUND = 19312;
    private static final int MINUTE = 60000;
    private static NfcAdapter mNfcAdapter;
    private static ReadingData mResult = new ReadingData(PredictionData.Result.ERROR_NO_NFC);
    private static boolean foreground_enabled = false;
    private static boolean tag_discovered = false;
    private static long last_tag_discovered = -1;
    private static boolean last_read_succeeded = false;
    private static final Object tag_lock = new Object();
    private static final Lock read_lock = new ReentrantLock();
    private static final boolean useReaderMode = true;
    private static boolean nfc_enabled = false;


    public static void stopNFC(Activity context) {
        if (foreground_enabled) {
            try {
                NfcAdapter.getDefaultAdapter(context).disableForegroundDispatch(context);
            } catch (Exception e) {
                Log.d(TAG, "Got exception disabling foregrond dispatch");
            }
            foreground_enabled = false;
        }
    }

    public static boolean useNFC() {
        return Home.getPreferencesBooleanDefaultFalse("use_nfc_scan") && (DexCollectionType.hasLibre());
    }

    @SuppressLint("NewApi")
    public static void disableNFC(final Activity context) {
        if (nfc_enabled) {
            try {
                if ((Build.VERSION.SDK_INT >= 19) && (useReaderMode)) {
                    Log.d(TAG, "Shutting down NFC reader mode");
                    mNfcAdapter.disableReaderMode(context);
                    nfc_enabled = false;
                }
                // TODO ALSO handle api < 19 ?
            } catch (Exception e) {
                //
            }
        }
    }

    @SuppressLint("NewApi")
    public static void doNFC(final Activity context) {

        if (!useNFC()) return;

        mNfcAdapter = NfcAdapter.getDefaultAdapter(context);

        if (mNfcAdapter == null) {

            JoH.static_toast_long("Phone has no NFC reader");
            //finish();
            return;

        } else if (!mNfcAdapter.isEnabled()) {
            JoH.static_toast_long("NFC is not enabled");
            return;
        }

        nfc_enabled = true;

        NfcManager nfcManager = (NfcManager) context.getSystemService(Context.NFC_SERVICE);
        if (nfcManager != null) {
            mNfcAdapter = nfcManager.getDefaultAdapter();
        }

        if (mNfcAdapter != null) {
            try {
                mNfcAdapter.isEnabled();
            } catch (NullPointerException e) {
                return;
            }
            // some superstitious code here
            try {
                mNfcAdapter.isEnabled();
            } catch (NullPointerException e) {
                return;
            }


            if ((Build.VERSION.SDK_INT >= 19) && (useReaderMode)) {
                try {
                    mNfcAdapter.disableReaderMode(context);
                    final Bundle options = new Bundle();
                    options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000);
                    mNfcAdapter.enableReaderMode(context, new NfcAdapter.ReaderCallback() {
                        @Override
                        public void onTagDiscovered(Tag tag) {
                            Log.d(TAG, "Reader mode tag discovered");
                            doTheScan(context, tag, false);
                        }
                    }, NfcAdapter.FLAG_READER_NFC_V | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, options);
                } catch (NullPointerException e) {
                    Log.wtf(TAG, "Null pointer exception from NFC subsystem: " + e.toString());
                }
            } else {
                PendingIntent pi = context.createPendingResult(REQ_CODE_NFC_TAG_FOUND, new Intent(), 0);
                if (pi != null) {
                    try {
                        mNfcAdapter.enableForegroundDispatch(
                                context,
                                pi,
                                new IntentFilter[]{
                                        new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
                                },
                                new String[][]{
                                        new String[]{"android.nfc.tech.NfcV"}
                                });
                        foreground_enabled = true;
                    } catch (NullPointerException e) {
                        //
                    }
                }
            }
        }


    }

    private static synchronized void doTheScan(final Activity context, Tag tag, boolean showui) {
        synchronized (tag_lock) {
            if (!tag_discovered) {
                if (!useNFC()) return;
                if ((!last_read_succeeded) && (JoH.ratelimit("nfc-debounce", 5)) || (JoH.ratelimit("nfc-debounce", 60))) {
                    tag_discovered = true;
                    Home.staticBlockUI(context, true);
                    last_tag_discovered = JoH.tsl();
                    if (showui) {
                        context.startActivity(new Intent(context, NFCScanningX.class));
                    } else {
                        NFCReaderX.vibrate(context, 0);
                        JoH.static_toast_short("Scanning");
                    }
                    if (d)
                        Log.d(TAG, "NFC tag discovered - going to read data");
                    new NfcVReaderTask(context).executeOnExecutor(xdrip.executor, tag);
                } else {
                    if (JoH.tsl() - last_tag_discovered > 5000) {
                        vibrate(context, 4);
                        JoH.static_toast_short("Not so quickly, wait 60 seconds");
                    }
                }
            } else {
                Log.d(TAG, "Tag already discovered!");
                if (JoH.tsl() - last_tag_discovered > 60000)
                    tag_discovered = false; // don't lock too long
            }
        } // lock
    }


    // via intents
    public static void tagFound(Activity context, Intent data) {

        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(data.getAction())) {
            Tag tag = data.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            doTheScan(context, tag, true);
        }
    }


    private static class NfcVReaderTask extends AsyncTask<Tag, Void, Tag> {

        Activity context;
        boolean succeeded = false;

        public NfcVReaderTask(Activity context) {
            this.context = context;
            last_read_succeeded = false;
            JoH.ratelimit("nfc-debounce", 1); // ping the timer
        }

        private byte[] data = new byte[360];


        @Override
        protected void onPostExecute(Tag tag) {
            try {
                if (tag == null) return;
                if (!NFCReaderX.useNFC()) return;
                if (succeeded) {
                    final String tagId = bytesToHexString(tag.getId());

                    mResult = parseData(0, tagId, data);
                    new Thread() {
                        @Override
                        public void run() {
                            LibreAlarmReceiver.processReadingDataTransferObject(new ReadingData.TransferObject(1, mResult));
                            Home.staticRefreshBGCharts();
                        }
                    }.start();
                } else {
                    Log.d(TAG, "Scan did not succeed so ignoring buffer");
                }
                Home.startHomeWithExtra(context, null, null);

            } catch (IllegalStateException e) {
                Log.e(TAG, "Illegal state exception in postExecute: " + e);

            } finally {
                tag_discovered = false; // right place?
                Home.staticBlockUI(context, false);
            }
        }


        @Override
        protected Tag doInBackground(Tag... params) {
            if (!NFCReaderX.useNFC()) return null;
            if (read_lock.tryLock()) {

                try {
                    Tag tag = params[0];
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        //
                    }
                    NfcV nfcvTag = NfcV.get(tag);
                    if (d) Log.d(TAG, "Attempting to read tag data");
                    try {
                        //boolean connected = false;
                        try {
                            nfcvTag.connect();
                        } catch (IOException e) {
                            Log.d(TAG, "Trying second nfc connect");
                            Thread.sleep(250);
                            nfcvTag.connect();
                        }
                        final byte[] uid = tag.getId();

                        final boolean multiblock = Home.getPreferencesBoolean("use_nfc_multiblock", true);
                        final boolean addressed = !Home.getPreferencesBoolean("use_nfc_any_tag", true);
                        // if multiblock mode
                        JoH.benchmark(null);

                        if (multiblock) {
                            final int correct_reply_size = addressed ? 28 : 25;
                            for (int i = 0; i <= 40; i = i + 3) {
                                final byte[] cmd;
                                if (addressed) {
                                    cmd = new byte[]{0x60, 0x23, 0, 0, 0, 0, 0, 0, 0, 0, (byte) i, 0x02};
                                    System.arraycopy(uid, 0, cmd, 2, 8);
                                } else {
                                    cmd = new byte[]{0x02, 0x23, (byte) i, 0x02};
                                }

                                byte[] replyBlock;
                                Long time = System.currentTimeMillis();
                                while (true) {
                                    try {
                                        replyBlock = nfcvTag.transceive(cmd);
                                        break;
                                    } catch (IOException e) {
                                        if ((System.currentTimeMillis() > time + 2000)) {
                                            Log.e(TAG, "tag read timeout");
                                            JoH.static_toast_short("NFC read timeout");
                                            vibrate(context, 3);
                                            return null;
                                        }
                                        Thread.sleep(100);
                                    }
                                }

                                if (d) Log.d(TAG, "Received multiblock reply, offset: " + i + " sized: " + replyBlock.length);
                                if (d) Log.d(TAG, HexDump.dumpHexString(replyBlock, 0, replyBlock.length));
                                if (replyBlock.length != correct_reply_size) {
                                    Log.e(TAG, "Incorrect block size: " + replyBlock.length + " vs " + correct_reply_size);
                                    JoH.static_toast_short("NFC invalid data");
                                    if (!addressed) {
                                        Home.setPreferencesBoolean("use_nfc_any_tag", false);
                                        JoH.static_toast_short("Turned off any-tag feature");
                                    }
                                    vibrate(context, 3);
                                    return null;
                                }
                                if (addressed) {
                                    for (int j = 0; j < 3; j++) {
                                        System.arraycopy(replyBlock, 2 + (j * 9), data, i * 8 + (j * 8), 8);
                                    }
                                } else {
                                    System.arraycopy(replyBlock, 1, data, i * 8, replyBlock.length - 1);
                                }
                            }
                        } else {
                            // always addressed
                            final int correct_reply_size = 10;
                            for (int i = 0; i <= 40; i++) {
                                final byte[] cmd = new byte[]{0x60, 0x20, 0, 0, 0, 0, 0, 0, 0, 0, (byte) i, 0};
                                System.arraycopy(uid, 0, cmd, 2, 8);
                                byte[] oneBlock;
                                Long time = System.currentTimeMillis();
                                while (true) {
                                    try {
                                        oneBlock = nfcvTag.transceive(cmd);
                                        break;
                                    } catch (IOException e) {
                                        if ((System.currentTimeMillis() > time + 2000)) {
                                            Log.e(TAG, "tag read timeout");
                                            JoH.static_toast_short("NFC read timeout");
                                            vibrate(context, 3);
                                            return null;
                                        }
                                        Thread.sleep(100);
                                    }
                                }
                                if (d) Log.d(TAG, HexDump.dumpHexString(oneBlock, 0, oneBlock.length));
                                if (oneBlock.length != correct_reply_size) {
                                    Log.e(TAG, "Incorrect block size: " + oneBlock.length + " vs " + correct_reply_size);
                                    JoH.static_toast_short("NFC invalid data");
                                    vibrate(context, 3);
                                    return null;
                                }
                                System.arraycopy(oneBlock, 2, data, i * 8, 8);
                            }
                        }
                        JoH.benchmark("Tag read");
                        Log.d(TAG, "GOT TAG DATA!");
                        last_read_succeeded = true;
                        succeeded = true;
                        vibrate(context, 1);
                        JoH.static_toast_short("Scanned OK!");

                    } catch (IOException e) {
                        JoH.static_toast_short("NFC IO Error");
                        vibrate(context, 3);
                    } catch (Exception e) {
                        Log.i(TAG, "Got exception reading nfc in background: " + e.toString());
                        return null;
                    } finally {
                        try {
                            nfcvTag.close();
                        } catch (Exception e) {
                            Log.e(TAG, "Error closing tag!");
                            JoH.static_toast_short("NFC Error");
                            vibrate(context, 3);
                        }
                    }
                    if (d) Log.d(TAG, "Tag data reader exiting");
                    return tag;
                } finally {
                    read_lock.unlock();
                    Home.staticBlockUI(context, false);
                }
            } else {
                Log.d(TAG, "Already read_locked! - skipping");
                return null;
            }

        }

    }

    private static String bytesToHexString(byte[] src) {
        StringBuilder builder = new StringBuilder("");
        if (src == null || src.length <= 0) {
            return "";
        }

        char[] buffer = new char[2];
        for (byte b : src) {
            buffer[0] = Character.forDigit((b >>> 4) & 0x0F, 16);
            buffer[1] = Character.forDigit(b & 0x0F, 16);
            builder.append(buffer);
        }

        return builder.toString();
    }

    public static ReadingData parseData(int attempt, String tagId, byte[] data) {
        long ourTime = System.currentTimeMillis();

        int indexTrend = data[26] & 0xFF;

        int indexHistory = data[27] & 0xFF;

        final int sensorTime = 256 * (data[317] & 0xFF) + (data[316] & 0xFF);

        long sensorStartTime = ourTime - sensorTime * MINUTE;

        ArrayList<GlucoseData> historyList = new ArrayList<>();


        // loads history values (ring buffer, starting at index_trent. byte 124-315)
        for (int index = 0; index < 32; index++) {
            int i = indexHistory - index - 1;
            if (i < 0) i += 32;
            GlucoseData glucoseData = new GlucoseData();
            // glucoseData.glucoseLevel =
            //       getGlucose(new byte[]{data[(i * 6 + 125)], data[(i * 6 + 124)]});

            glucoseData.glucoseLevelRaw =
                    getGlucoseRaw(new byte[]{data[(i * 6 + 125)], data[(i * 6 + 124)]});

            int time = Math.max(0, Math.abs((sensorTime - 3) / 15) * 15 - index * 15);

            glucoseData.realDate = sensorStartTime + time * MINUTE;
            glucoseData.sensorId = tagId;
            glucoseData.sensorTime = time;
            historyList.add(glucoseData);
        }


        ArrayList<GlucoseData> trendList = new ArrayList<>();

        // loads trend values (ring buffer, starting at index_trent. byte 28-123)
        for (int index = 0; index < 16; index++) {
            int i = indexTrend - index - 1;
            if (i < 0) i += 16;
            GlucoseData glucoseData = new GlucoseData();
            // glucoseData.glucoseLevel =
            //         getGlucose(new byte[]{data[(i * 6 + 29)], data[(i * 6 + 28)]});

            glucoseData.glucoseLevelRaw =
                    getGlucoseRaw(new byte[]{data[(i * 6 + 29)], data[(i * 6 + 28)]});
            int time = Math.max(0, sensorTime - index);

            glucoseData.realDate = sensorStartTime + time * MINUTE;
            glucoseData.sensorId = tagId;
            glucoseData.sensorTime = time;
            trendList.add(glucoseData);
        }


        return new ReadingData(null, trendList, historyList);
    }


    private static int getGlucoseRaw(byte[] bytes) {
        return ((256 * (bytes[0] & 0xFF) + (bytes[1] & 0xFF)) & 0x0FFF);
    }

    public static void vibrate(Context context, int pattern) {

        // 0 = scanning
        // 1 = scan ok
        // 2 = warning
        // 3 = lesser error

        final long[][] patterns = {{0, 150}, {0, 150, 70, 150}, {0, 2000}, {0, 1000}, {0, 100}};

        if (Home.getPreferencesBooleanDefaultFalse("nfc_scan_vibrate")) {
            final Vibrator vibrate = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if ((vibrate == null) || (!vibrate.hasVibrator())) return;
            vibrate.cancel();
            if (d) Log.d(TAG, "About to vibrate, pattern: " + pattern);
            try {
                vibrate.vibrate(patterns[pattern], -1);
            } catch (Exception e) {
                Log.d(TAG, "Exception in vibrate: " + e);
            }
        }
    }

    public static void handleHomeScreenScanPreference(Context context) {
        handleHomeScreenScanPreference(context, useNFC() && Home.getPreferencesBooleanDefaultFalse("nfc_scan_homescreen"));
    }

    public static void handleHomeScreenScanPreference(Context context, boolean state) {
        try {
            Log.d(TAG, "HomeScreen Scan State: " + (state ? "enable" : "disable"));
            context.getPackageManager().setComponentEnabledSetting(new ComponentName(context, NFCFilterX.class),
                    state ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.wtf(TAG, "Exception in handleHomeScreenScanPreference: " + e);
        }
    }

    public static synchronized void scanFromActivity(final Activity context, final Intent intent) {
        if (NFCReaderX.useNFC()) {
            // sanity checking is in onward function
            final Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        context.finish();
                    } catch (Exception e) {
                        //
                    }
                }
            }, 10000);
            if (JoH.ratelimit("nfc-filterx", 5)) {
                NFCReaderX.vibrate(context, 0);

                NFCReaderX.tagFound(context, intent);
            } else {
                Log.e(TAG, "Rate limited start nfc-filterx");
            }
        } else {
            context.finish();
        }
    }

    public static void windowFocusChange(final Activity context, boolean hasFocus, View decorView) {
        if (hasFocus) {
            if (Build.VERSION.SDK_INT >= 19) {
                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            } else {
                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN);
            }
        } else {
            final Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        context.finish();
                    } catch (Exception e) {
                        //
                    }
                }
            }, 1000);
        }
    }
}
