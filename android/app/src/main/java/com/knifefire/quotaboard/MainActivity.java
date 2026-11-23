package com.knifefire.quotaboard;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(LoginCapturePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
