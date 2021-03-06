package com.dairyroadsolutions.accelplot;

import android.app.Activity;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

public class MainActivity extends Activity {

    private static final int TRACE_COUNT = 4;
    private static final int CHART_COLUMN_COUNT = 1;
    private static final int SCREEN_BUFFER_COUNT = 3000;
    private static final int LINE_THICKNESS = 3;
    private static final boolean CYCLIC = true;

    // I truncated the accelerometer outputs from 2^14 bits to 2^11 bits to allow for the
    // address to included in the 2-byte structure. See "Firmware.ino" for the implementation
    // details
    private static final float F_ACCEL_COUNTS_PER_G = 512;
    private static final float F_ADC_COUNTS_PER_VOLT = 1024.0f/5.0f;

    // The plot area for each trace has to be scaled to +1 to -1
    private static final float F_SCALE_FACTOR_ACC = 0.50f/2048.0f;
    private static final float F_SCALE_FACTOR_GYRO = 0.25f/1024f;

    // Grid controls. It works best if they are even numbers
    private static final int I_DIVISIONS_X = 20;
    private static final int I_DIVISIONS_Y = 4;
    private static final float G_PER_DIV =(0.5f/F_SCALE_FACTOR_ACC)/(I_DIVISIONS_Y*F_ACCEL_COUNTS_PER_G);
    private static final float V_PER_DIV =(0.5f/F_SCALE_FACTOR_GYRO)/(I_DIVISIONS_Y*F_ADC_COUNTS_PER_VOLT);
    private float fTimePerDiv = 0;
    TextView[] tvTrace = new TextView[TRACE_COUNT+1];
    private int iLabelSize;

    // Chart trace controls
    private GLSurfaceView glChartSurfaceView;
    private float fChScale[];
    private float fChOffset[];
    private static ToggleButton _tbStreamToggleButton;
    private static ToggleButton _tbSaveData;
    private static ToggleButton _tbAudioOut;
    private static RadioGroup _rgCh1;
    private static RadioGroup _rgCh2;

    private FilterHelper filter = new FilterHelper();

    // Data writing controls
    private boolean bWriteLocal = false;

    // debug
    private static final String strTag = MainActivity.class.getSimpleName();

    // Application preferences
    private static SharedPreferences sharedPref;

    // Arduino values are stored in the Bluetooth.java object

    // NOTE: All sampling and filtering information is defined
    // in the Bluetooth.java class.

    // Data write is in the Bluetooth class

    /**
     * Set the channel scale factor.  Each trace could have a different scale
     * factor, but for this instance all traces will be set to the same value.
     * @param fScaleFactor Universal scale factor
     */
    public void setChScale(float fScaleFactor){

        // Populate the array with the scale factors for the accelerometer channels
        for( int idx = 0; idx < (TRACE_COUNT-1); ++idx) {
            fChScale[idx] = fScaleFactor/(TRACE_COUNT +1.0f);
        }

        // The scale factor for the gyro/ADC channel is set separately from the accels
        fChScale[TRACE_COUNT-1]=F_SCALE_FACTOR_GYRO;

        // Update dependent objects
        Bluetooth.classChartRenderer.setChScale(fChScale);

    }

    /**
     * Set the channel offsets to avoid overlaying the data
     */
    public void setChOffset(){

        float fIncrement = Bluetooth.classChartRenderer.classChart.classTraceHelper.getTraceIncrement();
        float fStart = Bluetooth.classChartRenderer.classChart.classTraceHelper.getTraceStart();

        // Populate the array with the scale factors
        for( int idx = 0; idx< TRACE_COUNT; ++idx) {
            fChOffset[idx] = fStart;
            fStart = fStart + fIncrement;
        }

        // Update the dependent objects
        Bluetooth.classChartRenderer.setChOffset(fChOffset);

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        // Setup the gl surface
        LinearLayout llControlLayer = (LinearLayout)findViewById(R.id.Chart);
        glChartSurfaceView = new GLSurfaceView(this);
        glChartSurfaceView.setEGLConfigChooser(false);
        llControlLayer.addView(glChartSurfaceView);


        // Scale the location of the grid division labels
        DisplayMetrics displaymetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);
        int iHeightDisp = displaymetrics.heightPixels;
//        int width = displaymetrics.widthPixels;

        // We have to scale the labels for smaller displays
        iLabelSize = 15;
        if( iHeightDisp < 750 ){
            iLabelSize = (int)((float)iHeightDisp / 50.0f);
        }


        // Add the vertical axis labels
        FrameLayout flTemp = (FrameLayout)findViewById(R.id.flChartStuff);

        int idxText;
        for( idxText = 0; idxText<=TRACE_COUNT; ++idxText){
            tvTrace[idxText] = new TextView(this);
            tvTrace[idxText].setText("");
            tvTrace[idxText].setTextSize(iLabelSize);
            tvTrace[idxText].setPadding((iLabelSize/2)+1,(iLabelSize/2)+1, 0, 0);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            flTemp.addView(tvTrace[idxText], params);

        }

        // Horizontal axis labels
        --idxText;
        tvTrace[idxText].setPadding((iLabelSize/2)+1,(iLabelSize/2)+1, (iLabelSize/2)+1, (iLabelSize/2)+1);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, (Gravity.BOTTOM | Gravity.RIGHT) );
        tvTrace[idxText].setLayoutParams(params);

        // User prefs
        sharedPref = getPreferences(Context.MODE_PRIVATE);

        // Flags and initialization of the BlueTooth object
        Bluetooth.samplesBuffer=new SamplesBuffer[TRACE_COUNT];
        Bluetooth.vSetWriteLocal(bWriteLocal);

        // Flags for the data stream button
        _tbStreamToggleButton = (ToggleButton)findViewById(R.id.tbStream);
        _tbStreamToggleButton.setEnabled(false);

        // Flags for the data save button
        _tbSaveData = (ToggleButton)findViewById(R.id.tbSaveData);
        _tbSaveData.setEnabled(false);

        // Flags and init for the audio out
        _tbAudioOut = (ToggleButton)findViewById(R.id.tbAudioOut);
        _tbAudioOut.setEnabled(false);

        // Initialze audio mappings
        _rgCh1 = (RadioGroup)findViewById(R.id.radio_ADC_to_Ch1);
        _rgCh2 = (RadioGroup)findViewById(R.id.radio_ADC_to_Ch2);
        vGetUserPrefs();
        vUpdateChMapsEnabled(false);

        for(int i=0;i< TRACE_COUNT;i++)
        {
            Bluetooth.samplesBuffer[i] = new SamplesBuffer(SCREEN_BUFFER_COUNT, true);
        }

        Bluetooth.classChartRenderer = new ChartRenderer(this,SCREEN_BUFFER_COUNT,Bluetooth.samplesBuffer, TRACE_COUNT);
        Bluetooth.classChartRenderer.setCyclic(CYCLIC);
        Bluetooth.classChartRenderer.bSetDivisionsX(I_DIVISIONS_X);
        Bluetooth.classChartRenderer.bSetDivisionsY(I_DIVISIONS_Y);

        fChScale = new float[TRACE_COUNT];
        setChScale(F_SCALE_FACTOR_ACC);

        fChOffset = new float[TRACE_COUNT];
        setChOffset();

        // Line thickness
        Bluetooth.classChartRenderer.setThickness(LINE_THICKNESS);

        // Number of columns of chart data
        Bluetooth.classChartRenderer.setChartColumnCount(CHART_COLUMN_COUNT);

        glChartSurfaceView.setRenderer(Bluetooth.classChartRenderer);

        // Initialize the Bluetooth object
        init();

        // Initialize the buttons
        ButtonInit();

        // Debug code to test display of screen buffers
        for( int idx=0; idx<SCREEN_BUFFER_COUNT; ++idx){
            Bluetooth.samplesBuffer[1].addSample((float)(idx>>2));
        }

        // Testing calls here. Remove comments to run them.
        filter.TestHarness();
        Bluetooth.samplesBuffer[0].TestHarness();

    }


    /**
     * Pass the message handler to the Bluetooth class
     */
    void init() {
        Bluetooth.gethandler(mUpdateHandler);
    }


    /**
     * When streaming stops, this need to be halted as well
     */
    private void vStopStreamDep(){

        _tbSaveData.setChecked(false);
        _tbSaveData.setEnabled(false);
        vUpdateSaveData();

        vUpdateChMapsEnabled(false);
        _tbAudioOut.setChecked(false);
        _tbAudioOut.setEnabled(false);
        vUpdateAudioOut();

    }

    /**
     * Handles process that should be affected by change in the SaveData button status
     */
    private void vUpdateSaveData(){
        bWriteLocal = _tbStreamToggleButton.isChecked();
        Bluetooth.vSetWriteLocal(bWriteLocal);
        Bluetooth.vSetWritePending(true);
    }

    /**
     * Update the radio buttons to reflect the internal state of the Bluetooth buttons.
     */
    private void vUpdateChMaps(){

        // Channel 1
        if(Bluetooth.isbADC1ToCh1Out()){
            _rgCh1.check(R.id.radio_ADC1_Ch1);
        }
        if(Bluetooth.isbADC2ToCh1Out()){
            _rgCh1.check(R.id.radio_ADC2_Ch1);
        }
        if(Bluetooth.isbADC3ToCh1Out()){
            _rgCh1.check(R.id.radio_ADC3_Ch1);
        }

        //Channel 2
        if(Bluetooth.isbADC1ToCh2Out()){
            _rgCh2.check(R.id.radio_ADC1_Ch2);
        }
        if(Bluetooth.isbADC2ToCh2Out()){
            _rgCh2.check(R.id.radio_ADC2_Ch2);
        }
        if(Bluetooth.isbADC3ToCh2Out()){
            _rgCh2.check(R.id.radio_ADC3_Ch2);
        }

    }

    /**
     * Toggles the enabled status of the audio channel mapping buttons
     * @param bEnabled  New enabled status value
     */
    private void vUpdateChMapsEnabled(boolean bEnabled){

        int iRadBut = _rgCh1.getChildCount();
        RadioButton rbTemp;

        for (int iBut=0; iBut<iRadBut; iBut++){
            rbTemp = ((RadioButton) _rgCh1.getChildAt(iBut));
            rbTemp.setEnabled( bEnabled );
        }

        for (int iBut=0; iBut<iRadBut; iBut++){
            rbTemp = ((RadioButton) _rgCh2.getChildAt(iBut));
            rbTemp.setEnabled( bEnabled );
        }
    }


    /**
     * Get the user preferences
     */
    private void vGetUserPrefs(){

        Bluetooth.setbADC1ToCh1Out(sharedPref.getBoolean(getString(R.string.radio_ADC1_Ch1), true));
        Bluetooth.setbADC2ToCh1Out(sharedPref.getBoolean(getString(R.string.radio_ADC2_Ch1), true));
        Bluetooth.setbADC3ToCh1Out(sharedPref.getBoolean(getString(R.string.radio_ADC3_Ch1), true));

        Bluetooth.setbADC1ToCh2Out(sharedPref.getBoolean(getString(R.string.radio_ADC1_Ch2), true));
        Bluetooth.setbADC2ToCh2Out(sharedPref.getBoolean(getString(R.string.radio_ADC2_Ch2), true));
        Bluetooth.setbADC3ToCh2Out(sharedPref.getBoolean(getString(R.string.radio_ADC3_Ch2), true));

        vUpdateChMaps();

    }
    /**
     * Update the user preferences
     */
    private void vUpdateUserPrefs(){

        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putBoolean(getString(R.string.radio_ADC1_Ch1), Bluetooth.isbADC1ToCh1Out());
        editor.putBoolean(getString(R.string.radio_ADC2_Ch1), Bluetooth.isbADC2ToCh1Out());
        editor.putBoolean(getString(R.string.radio_ADC3_Ch1), Bluetooth.isbADC3ToCh1Out());
        editor.putBoolean(getString(R.string.radio_ADC1_Ch2), Bluetooth.isbADC1ToCh2Out());
        editor.putBoolean(getString(R.string.radio_ADC2_Ch2), Bluetooth.isbADC2ToCh2Out());
        editor.putBoolean(getString(R.string.radio_ADC3_Ch2), Bluetooth.isbADC3ToCh2Out());
        editor.commit();

    }


    /**
     * Handles process that change with the AudioOut button status
     */
    private void vUpdateAudioOut(){
        Bluetooth.bAudioOut = _tbAudioOut.isChecked();
        Bluetooth.classAudioHelper.vSetAudioOut(Bluetooth.bAudioOut);
    }

    /**
     * This function locks the orientation. The real problem is that my code doesn't handle
     * orientation changes while streaming data from the Bluetooth device.
     * TODO - understand what needs to be changed to allow orientation changes while streaming.
     */
    private void vLockOrient(){
        int currentOrientation = getResources().getConfiguration().orientation;
        if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
        else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
    }

    private void vUpdateGridLabels(){

        FrameLayout flTemp = (FrameLayout)findViewById(R.id.flChartStuff);
        int iDiff = (int)((float)flTemp.getHeight()/(float)TRACE_COUNT);

        // Accelerometer labels
        int idxText;
        for( idxText = 0; idxText<TRACE_COUNT; ++idxText){

            tvTrace[idxText].setText("Ch" + String.valueOf(idxText+1) + ", " + String.valueOf(G_PER_DIV) + "g's per div");
            if(idxText == (TRACE_COUNT-1)){
                tvTrace[idxText].setText("Ch" + String.valueOf(idxText+1) + ", " + String.valueOf(V_PER_DIV) + " volt per div");
            }
            tvTrace[idxText].setBackgroundColor(Color.BLACK);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            params.setMargins((iLabelSize/2)+1,(iLabelSize/2)+1+(iDiff*idxText),0,0);
            tvTrace[idxText].setLayoutParams(params);

        }

        // Horizontal label goodness
        fTimePerDiv = SCREEN_BUFFER_COUNT / ((float)Bluetooth.dGetSamplingFrequency() * (float)I_DIVISIONS_X );
        tvTrace[idxText].setText(String.valueOf(fTimePerDiv) + " sec. per div");

    }

    /**
     * Initialize the button controls and listeners
     */
    private void ButtonInit(){

        Button btnConnectButton;
        Button btnDiscconnectButton;

        // Configure the stream data button
        _tbStreamToggleButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {

                // Lock the orientation
                vLockOrient();

                // This section handles the thread
                if (Bluetooth.connectedThread != null)
                {
                    Bluetooth.bStreamData = _tbStreamToggleButton.isChecked();
                }

                // This section handles the dependant buttons
                if (_tbStreamToggleButton.isChecked()){

                    _tbSaveData.setEnabled(true);
                    vUpdateChMapsEnabled(true);
                    _tbAudioOut.setEnabled(true);
                    vUpdateGridLabels();

                }else{
                    vStopStreamDep();
                }
            }

        });

        // Configure the Bluetooth connect button
        btnConnectButton = (Button)findViewById(R.id.bConnect);
        btnConnectButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                startActivity(new Intent("android.intent.action.BT1"));

            }


        });

        // Configure the Bluetooth disconnect button
        btnDiscconnectButton = (Button)findViewById(R.id.bDisconnect);
        btnDiscconnectButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                _tbStreamToggleButton.setChecked(false);
                Bluetooth.bStreamData = false;
                Bluetooth.disconnect();
                vStopStreamDep();
            }


        });

        // Configure the save data button
        _tbSaveData = (ToggleButton)findViewById(R.id.tbSaveData);
        _tbSaveData.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                vUpdateSaveData();
            }
        });

        // Configure the channel 1 radio buttons
        _rgCh1.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {

            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {

                // Check which radio button was clicked
                switch(checkedId) {
                    case R.id.radio_ADC1_Ch1:
                        Bluetooth.setbADC1ToCh1Out(true);
                        Bluetooth.setbADC2ToCh1Out(false);
                        Bluetooth.setbADC3ToCh1Out(false);
//                        Log.d(strTag, ":HM:                     ADC1_Ch1 Active: ");
                        break;
                    case R.id.radio_ADC2_Ch1:
                        Bluetooth.setbADC1ToCh1Out(false);
                        Bluetooth.setbADC2ToCh1Out(true);
                        Bluetooth.setbADC3ToCh1Out(false);
//                        Log.d(strTag, ":HM:                     ADC2_Ch1 Active: ");
                        break;
                    case R.id.radio_ADC3_Ch1:
                        Bluetooth.setbADC1ToCh1Out(false);
                        Bluetooth.setbADC2ToCh1Out(false);
                        Bluetooth.setbADC3ToCh1Out(true);
//                        Log.d(strTag, ":HM:                     ADC3_Ch1 Active: ");
                        break;
                }

                vUpdateUserPrefs();

            }
        });

        // Configure the channel 2 radio buttons
        _rgCh2.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {

            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {

                // Check which radio button was clicked
                switch(checkedId) {
                    case R.id.radio_ADC1_Ch2:
                        Bluetooth.setbADC1ToCh2Out(true);
                        Bluetooth.setbADC2ToCh2Out(false);
                        Bluetooth.setbADC3ToCh2Out(false);
//                        Log.d(strTag, ":HM:                     ADC1_Ch2 Active: ");
                        break;
                    case R.id.radio_ADC2_Ch2:
                        Bluetooth.setbADC1ToCh2Out(false);
                        Bluetooth.setbADC2ToCh2Out(true);
                        Bluetooth.setbADC3ToCh2Out(false);
//                        Log.d(strTag, ":HM:                     ADC2_Ch2 Active: ");
                        break;
                    case R.id.radio_ADC3_Ch2:
                        Bluetooth.setbADC1ToCh2Out(false);
                        Bluetooth.setbADC2ToCh2Out(false);
                        Bluetooth.setbADC3ToCh2Out(true);
//                        Log.d(strTag, ":HM:                     ADC3_Ch2 Active: ");
                        break;
                }

                vUpdateUserPrefs();

            }
        });

        // Configure the audio out button
        _tbAudioOut = (ToggleButton)findViewById(R.id.tbAudioOut);
        _tbAudioOut.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                vUpdateAudioOut();
            }
        });

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    Handler mUpdateHandler = new Handler()
    {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch(msg.what){

                case Bluetooth.SUCCESS_DISCONNECT:
                    Toast.makeText(getApplicationContext(), "Disconnected!", Toast.LENGTH_LONG).show();
                    _tbStreamToggleButton.setEnabled(false);
                    break;

                case Bluetooth.SUCCESS_CONNECT:
                    Bluetooth.connectedThread = new Bluetooth.ConnectedThread((BluetoothSocket)msg.obj);
                    Toast.makeText(getApplicationContext(), "Connected!", Toast.LENGTH_LONG).show();
                    Bluetooth.connectedThread.start();
                    _tbStreamToggleButton.setEnabled(true);
                    break;

                case Bluetooth.FILE_WRITE_DONE:
                    _tbSaveData.setChecked(false);
                    break;
            }

        }

    };

}