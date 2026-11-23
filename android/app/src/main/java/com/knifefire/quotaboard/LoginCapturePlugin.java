package com.knifefire.quotaboard;

import android.content.Intent;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import androidx.activity.result.ActivityResult;

@CapacitorPlugin(name = "LoginCapture")
public class LoginCapturePlugin extends Plugin {

    private static final int REQ_LOGIN = 9001;

    @PluginMethod
    public void openLogin(PluginCall call) {
        String provider = call.getString("provider", "glm-cn");
        Intent intent = new Intent(getContext(), LoginActivity.class);
        intent.putExtra(LoginActivity.EXTRA_PROVIDER, provider);
        startActivityForResult(call, intent, "handleLoginResult");
    }

    @ActivityCallback
    private void handleLoginResult(PluginCall call, ActivityResult result) {
        Intent data = result.getData();
        if (data == null || result.getResultCode() != android.app.Activity.RESULT_OK) {
            call.resolve(new JSObject().put("cancelled", true));
            return;
        }
        JSObject ret = new JSObject();
        ret.put("cancelled", false);
        ret.put("token", data.getStringExtra(LoginActivity.RESULT_TOKEN));
        ret.put("authMode", data.getStringExtra(LoginActivity.RESULT_AUTHMODE));
        ret.put("org", data.getStringExtra(LoginActivity.RESULT_ORG));
        ret.put("project", data.getStringExtra(LoginActivity.RESULT_PROJECT));
        ret.put("planType", data.getStringExtra(LoginActivity.RESULT_PLANTYPE));
        call.resolve(ret);
    }
}
