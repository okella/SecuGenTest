package com.example.sergeymarkin.secugentest;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.ToggleButton;

import com.example.sergeymarkin.secugentest.help.ImageHelp;

import java.util.Arrays;

import SecuGen.Driver.Constant;
import SecuGen.FDxSDKPro.JSGFPLib;
import SecuGen.FDxSDKPro.SGAutoOnEventNotifier;
import SecuGen.FDxSDKPro.SGDeviceInfoParam;
import SecuGen.FDxSDKPro.SGFDxConstant;
import SecuGen.FDxSDKPro.SGFDxDeviceName;
import SecuGen.FDxSDKPro.SGFDxErrorCode;
import SecuGen.FDxSDKPro.SGFDxSecurityLevel;
import SecuGen.FDxSDKPro.SGFDxTemplateFormat;
import SecuGen.FDxSDKPro.SGFingerInfo;
import SecuGen.FDxSDKPro.SGFingerPresentEvent;


public class MainActivity extends AppCompatActivity implements SGFingerPresentEvent {


    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private static final String TAG = "LOGD";
    private Button buttonCapture;
    private Button save;
    private Button led;
    private Button print_save;

    private SGAutoOnEventNotifier autoOn;
    private JSGFPLib jsgfpLib;
    private IntentFilter filter;
    private boolean usbPermissionRequested;
    private boolean bSecuGenDeviceOpened;
    private boolean ledOn;
    private PendingIntent mPermissionIntent;
    private int mImageWidth;
    private int mImageHeight;
    private int mImageDPI;
    private int[] mMaxTemplateSize;
    private int[] grayBuffer;
    private Bitmap grayBitmap;
    private byte[] mRegisterTemplate;
    private byte[] savedTemplate;
    private ImageView mImageViewFingerprint;
    private Spinner spinner;

    public Handler fingerDetectedHandler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            сaptureFingerPrint();
        }
    };

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if(device != null){
                            Log.d(TAG, "Vendor ID : " + device.getVendorId() + "\n");
                            Log.d(TAG, "Product ID: " + device.getProductId() + "\n");
                        }else{
                            Log.e(TAG, "mUsbReceiver.onReceive() Device is null");
                        }
                    }
                    else {
                        Log.d(TAG, "permission denied for device " + device);
                    }
                }
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        buttonCapture =  (Button)findViewById(R.id.buttonCapture);
        save =  (Button)findViewById(R.id.save);
        print_save =  (Button)findViewById(R.id.print_save);
        led =  (Button)findViewById(R.id.led);
        mImageViewFingerprint = (ImageView)findViewById(R.id.imageViewFingerprint);

        spinner = (Spinner) findViewById(R.id.spinner);

        //заполняем картинку
        grayBuffer = new int[JSGFPLib.MAX_IMAGE_WIDTH_ALL_DEVICES*JSGFPLib.MAX_IMAGE_HEIGHT_ALL_DEVICES];
        for (int i=0; i<grayBuffer.length; ++i)
            grayBuffer[i] = android.graphics.Color.GRAY;
        grayBitmap = Bitmap.createBitmap(JSGFPLib.MAX_IMAGE_WIDTH_ALL_DEVICES, JSGFPLib.MAX_IMAGE_HEIGHT_ALL_DEVICES, Bitmap.Config.ARGB_8888);
        grayBitmap.setPixels(grayBuffer, 0, JSGFPLib.MAX_IMAGE_WIDTH_ALL_DEVICES, 0, 0, JSGFPLib.MAX_IMAGE_WIDTH_ALL_DEVICES, JSGFPLib.MAX_IMAGE_HEIGHT_ALL_DEVICES);
        mImageViewFingerprint.setImageBitmap(grayBitmap);


        //USB Permissions
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        filter = new IntentFilter(ACTION_USB_PERMISSION);

        //Инициализация библиотеки
        jsgfpLib = new JSGFPLib((UsbManager)getSystemService(Context.USB_SERVICE));
        usbPermissionRequested = false;
        bSecuGenDeviceOpened = false;
        mMaxTemplateSize = new int[1];

        Log.e(TAG, "Starting Activity");
        Log.e(TAG, "Версия библиотеки: " + Integer.toHexString((int)jsgfpLib.Version()));

        //Запуск автоопределения
        autoOn = new SGAutoOnEventNotifier (jsgfpLib, this); autoOn.start();

        buttonCapture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                сaptureFingerPrint();
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (int i=0; i< mRegisterTemplate.length; ++i)
                    savedTemplate[i] = mRegisterTemplate[i];
                Log.d(TAG, "Save Template: " +Arrays.toString(savedTemplate));
            }
        });
        print_save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "Save Template: " +Arrays.toString(savedTemplate));
            }
        });
        ledOn= false;
        led.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(!ledOn){
                    jsgfpLib.SetLedOn(true);
                    ledOn=true;
                }else{
                    jsgfpLib.SetLedOn(false);
                    ledOn=false;
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mUsbReceiver, filter);
        long error_init = jsgfpLib.Init(SGFDxDeviceName.SG_DEV_AUTO);
        Log.e(TAG, "jsgfpLib.Init: "+error_init);
        if (error_init != SGFDxErrorCode.SGFDX_ERROR_NONE){
            if (error_init == SGFDxErrorCode.SGFDX_ERROR_DEVICE_NOT_FOUND){
                Log.e(TAG, "The attached fingerprint device is not supported on Android");
            } else {
                Log.e(TAG, "Fingerprint device initialization failed!");
            }
        }else {
            UsbDevice usbDevice = jsgfpLib.GetUsbDevice();
            if (usbDevice == null){
                Log.e(TAG, "SecuGen fingerprint sensor not found!");
            }else {
                boolean hasPermission = jsgfpLib.GetUsbManager().hasPermission(usbDevice);
                if (!hasPermission) {
                    if (!usbPermissionRequested){
                        Log.e(TAG, "Requesting USB Permission");
                        //Log.d(TAG, "Call GetUsbManager().requestPermission()");
                        usbPermissionRequested = true;
                        jsgfpLib.GetUsbManager().requestPermission(usbDevice, mPermissionIntent);
                    }else {
                        //wait up to 20 seconds for the system to grant USB permission
                        hasPermission = jsgfpLib.GetUsbManager().hasPermission(usbDevice);
                        Log.e(TAG, "Waiting for USB Permission");
                        int i=0;
                        while ((hasPermission == false) && (i <= 40)) {
                            ++i;
                            hasPermission = jsgfpLib.GetUsbManager().hasPermission(usbDevice);
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            Log.d(TAG, "Waited " + i*50 + " milliseconds for USB permission");
                        }
                    }
                }
                if (hasPermission) {
                    long error_open_device = jsgfpLib.OpenDevice(0);
                    Log.d(TAG, "OpenDevice() ret: " + error_open_device);
                    if (error_open_device == SGFDxErrorCode.SGFDX_ERROR_NONE) {
                        bSecuGenDeviceOpened = true;
                        SGDeviceInfoParam deviceInfo = new SGDeviceInfoParam();
                        long error_device_info = jsgfpLib.GetDeviceInfo(deviceInfo);
                        Log.d(TAG, "GetDeviceInfo() ret: " + error_device_info);
                        mImageWidth = deviceInfo.imageWidth;
                        mImageHeight = deviceInfo.imageHeight;
                        mImageDPI = deviceInfo.imageDPI;

                        Log.d(TAG, "///////////////////////////////////////////////////////////////////");
                        Log.d(TAG, "Image width: " + mImageWidth);
                        Log.d(TAG, "Image height: " + mImageHeight);
                        Log.d(TAG, "Image resolution: " + mImageDPI);
                        Log.d(TAG, "Serial Number: " + new String(deviceInfo.deviceSN()));
                        Log.d(TAG, "Port speed: " + deviceInfo.comSpeed);
                        Log.d(TAG, "Fingerprint sensor CMOS imager brightness setting: " + deviceInfo.brightness);
                        Log.d(TAG, "Fingerprint sensor CMOS imager gain setting: " + deviceInfo.gain);
                        Log.d(TAG, "///////////////////////////////////////////////////////////////////");
                        //Установка шаблона

                        int selected = 0;
                        Log.d(TAG, "spinner selected: " + selected);
                        switch(selected){
                            case 0:
                                jsgfpLib.SetTemplateFormat(SGFDxTemplateFormat.TEMPLATE_FORMAT_SG400);
                                jsgfpLib.GetMaxTemplateSize(mMaxTemplateSize);
                                Log.d(TAG, "TEMPLATE_FORMAT_SG400 SIZE: " + mMaxTemplateSize[0]);
                                break;
                            case 1:
                                jsgfpLib.SetTemplateFormat(SGFDxTemplateFormat.TEMPLATE_FORMAT_ANSI378 );
                                jsgfpLib.GetMaxTemplateSize(mMaxTemplateSize);
                                Log.d(TAG, "TEMPLATE_FORMAT_ANSI378  SIZE: " + mMaxTemplateSize[0]);
                                break;
                            case 2:
                                jsgfpLib.SetTemplateFormat(SGFDxTemplateFormat.TEMPLATE_FORMAT_ISO19794 );
                                jsgfpLib.GetMaxTemplateSize(mMaxTemplateSize);
                                Log.d(TAG, "TEMPLATE_FORMAT_ISO19794  SIZE: " + mMaxTemplateSize[0]);
                                break;
                        }

                        mRegisterTemplate = new byte[mMaxTemplateSize[0]];
                        savedTemplate = new byte[mMaxTemplateSize[0]];
                        //Вклчаем умное сканирование
                        jsgfpLib.WriteData(SGFDxConstant.WRITEDATA_COMMAND_ENABLE_SMART_CAPTURE, (byte) 1);

                        autoOn.start();
                    } else {
                        Log.d(TAG, "Waiting for USB Permission");
                    }
                }
            }
        }
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause()");
        if (bSecuGenDeviceOpened) {
            autoOn.stop();
            jsgfpLib.CloseDevice();
            bSecuGenDeviceOpened = false;
        }
        unregisterReceiver(mUsbReceiver);
        mRegisterTemplate = null;
        savedTemplate = null;
        mImageViewFingerprint.setImageBitmap(grayBitmap);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");
        jsgfpLib.CloseDevice();
        mRegisterTemplate = null;
        savedTemplate = null;
        jsgfpLib.Close();
        unregisterReceiver(mUsbReceiver);
        super.onDestroy();
    }

    public void SGFingerPresentCallback (){
        autoOn.stop();
        fingerDetectedHandler.sendMessage(new Message());
    }

    public void сaptureFingerPrint(){
        long dwTimeStart = 0, dwTimeEnd = 0, dwTimeElapsed = 0;
        dwTimeStart = System.currentTimeMillis();
        //получаем картинку
        byte[] buffer = new byte[mImageWidth*mImageHeight];
        long result = jsgfpLib.GetImageEx(buffer, 10000,50);
        //long result = jsgfpLib.GetImage(buffer);

        //вычисляем качество картинки
        long nfiq = jsgfpLib.ComputeNFIQ(buffer, mImageWidth, mImageHeight);
        String NFIQString =  new String("NFIQ="+ nfiq);

        dwTimeEnd = System.currentTimeMillis();
        dwTimeElapsed = dwTimeEnd-dwTimeStart;
        Log.d(TAG, "getImageEx(10000,50) ret:" + result + " [" + dwTimeElapsed + "ms]" + NFIQString);
        //отображаем картинку
        mImageViewFingerprint.setImageBitmap(ImageHelp.toGrayscale(buffer,mImageWidth,mImageHeight));
        //создаем шаблон

        int selected = 0;
        Log.d(TAG, "spinner selected: " + selected);
        boolean[] matched = new boolean[1];
        switch(selected){
            case 0:
                SGFingerInfo fpInfo = new SGFingerInfo();
                for (int i=0; i< mRegisterTemplate.length; ++i)
                    mRegisterTemplate[i] = 0;

                dwTimeStart = System.currentTimeMillis();
                result = jsgfpLib.CreateTemplate(fpInfo, buffer, mRegisterTemplate);
                dwTimeEnd = System.currentTimeMillis();
                dwTimeElapsed = dwTimeEnd-dwTimeStart;
                Log.d(TAG, "CreateTemplate() ret:" + result + " [" + dwTimeElapsed + "ms]");
                fpInfo = null;
                buffer = null;

                dwTimeStart = System.currentTimeMillis();
                result = jsgfpLib.MatchTemplate(savedTemplate ,mRegisterTemplate, SGFDxSecurityLevel.SL_NORMAL, matched);
                dwTimeEnd = System.currentTimeMillis();
                dwTimeElapsed = dwTimeEnd - dwTimeStart;
                Log.d(TAG, "MatchTemplate() ret:" + result + " [" + dwTimeElapsed + "ms]");

                break;
            case 1:
                for (int i=0; i< mRegisterTemplate.length; ++i)
                    mRegisterTemplate[i] = 0;

                dwTimeStart = System.currentTimeMillis();
                result = jsgfpLib.CreateTemplate(null, buffer, mRegisterTemplate);
                dwTimeEnd = System.currentTimeMillis();
                dwTimeElapsed = dwTimeEnd-dwTimeStart;
                Log.d(TAG, "CreateTemplate() ret:" + result + " [" + dwTimeElapsed + "ms]");
                buffer = null;

                dwTimeStart = System.currentTimeMillis();
                result = jsgfpLib.MatchAnsiTemplate(savedTemplate ,0,mRegisterTemplate,0, SGFDxSecurityLevel.SL_NORMAL, matched);
                dwTimeEnd = System.currentTimeMillis();
                dwTimeElapsed = dwTimeEnd - dwTimeStart;
                Log.d(TAG, "MatchTemplate() ret:" + result + " [" + dwTimeElapsed + "ms]");

                break;
            case 2:
                for (int i=0; i< mRegisterTemplate.length; ++i)
                    mRegisterTemplate[i] = 0;

                dwTimeStart = System.currentTimeMillis();
                result = jsgfpLib.CreateTemplate(null, buffer, mRegisterTemplate);
                dwTimeEnd = System.currentTimeMillis();
                dwTimeElapsed = dwTimeEnd-dwTimeStart;
                Log.d(TAG, "CreateTemplate() ret:" + result + " [" + dwTimeElapsed + "ms]");
                buffer = null;
                dwTimeStart = System.currentTimeMillis();

                //result = jsgfpLib.MatchIsoTemplate(savedTemplate ,0,mRegisterTemplate,0, SGFDxSecurityLevel.SL_NORMAL, matched);
                result = jsgfpLib.MatchTemplate(savedTemplate, mRegisterTemplate, SGFDxSecurityLevel.SL_NORMAL, matched);
                dwTimeEnd = System.currentTimeMillis();
                dwTimeElapsed = dwTimeEnd - dwTimeStart;
                Log.d(TAG, "MatchTemplate() ret:" + result + " [" + dwTimeElapsed + "ms]");

                break;
        }
        if (matched[0]) {
            Log.d(TAG, "Совпали!!");
        } else {
            Log.d(TAG, "НЕ совпали!!");
        }
        //вычисляем уровень совпадения
        int[] score = new int[1];
        dwTimeStart = System.currentTimeMillis();
        jsgfpLib.GetMatchingScore(mRegisterTemplate,savedTemplate,score);
        dwTimeEnd = System.currentTimeMillis();
        dwTimeElapsed = dwTimeEnd-dwTimeStart;
        String result_templ = Arrays.toString(score);
        Log.d(TAG, "GetMatchingScore()[" + dwTimeElapsed + "ms]");
        Log.d(TAG, "score "+result_templ);

        matched = null;
        autoOn.start();
    }
}
