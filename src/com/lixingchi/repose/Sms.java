package com.lixingchi.repose;

import android.content.*;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import org.json.JSONObject;

public final class Sms extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0) return;
        StringBuilder body = new StringBuilder();
        for (SmsMessage part : parts) {
            if (part == null || part.getMessageBody() == null) return;
            body.append(part.getMessageBody());
            if (body.length() > 4096) return;
        }
        PendingResult pending = goAsync();
        Client.IO.execute(() -> {
            try { Client.call(new JSONObject().put("op", "sms").put("message", body.toString())); }
            catch (Exception error) { Client.failure(context, "sms_" + error.getClass().getSimpleName()); }
            finally { pending.finish(); }
        });
    }
}
