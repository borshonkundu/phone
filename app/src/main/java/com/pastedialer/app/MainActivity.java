package com.pastedialer.app;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int RC_PERMS = 1;
    private static final int RC_SD = 2;
    private static final int RC_DIALER = 3;

    private EditText etInput;
    private TextView tvStatus;
    private ListView lvContacts;
    private ContactAdapter adapter;

    private final ArrayList<Contact> all = new ArrayList<>();
    private final ArrayList<Contact> shown = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---------------------------------------------------------------- model
    private static final class Contact {
        final String name;
        final String lname;
        final String number;
        final String digits;

        Contact(String name, String number) {
            this.name = name == null ? "" : name;
            this.lname = this.name.toLowerCase(Locale.getDefault());
            this.number = number == null ? "" : number;
            this.digits = digitsOnly(this.number);
        }
    }

    /** ইংরেজি ও বাংলা (যেকোনো ইউনিকোড) ডিজিটকে ASCII ডিজিটে রূপান্তর করে, বাকি সব বাদ দেয়। */
    private static String digitsOnly(CharSequence s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            int d = Character.digit(s.charAt(i), 10);
            if (d >= 0) sb.append((char) ('0' + d));
        }
        return sb.toString();
    }

    /** ডায়াল করার জন্য নম্বর পরিষ্কার করে: ডিজিট, +, *, # রাখে। */
    private static String sanitizeNumber(CharSequence s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int d = Character.digit(ch, 10);
            if (d >= 0) sb.append((char) ('0' + d));
            else if (ch == '+' || ch == '*' || ch == '#') sb.append(ch);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- adapter
    private static final class Holder {
        TextView name;
        TextView number;
    }

    private final class ContactAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            Holder h;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_contact, parent, false);
                h = new Holder();
                h.name = v.findViewById(R.id.tvName);
                h.number = v.findViewById(R.id.tvNumber);
                v.setTag(h);
            } else {
                h = (Holder) v.getTag();
            }
            Contact c = shown.get(position);
            h.name.setText(c.name.isEmpty() ? c.number : c.name);
            h.number.setText(c.number);
            return v;
        }
    }

    // ------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etInput = findViewById(R.id.etInput);
        tvStatus = findViewById(R.id.tvStatus);
        lvContacts = findViewById(R.id.lvContacts);
        Button btnPaste = findViewById(R.id.btnPaste);
        Button btnCall = findViewById(R.id.btnCall);
        Button btnDefault = findViewById(R.id.btnDefault);
        Button btnFolder = findViewById(R.id.btnFolder);
        CheckBox cbAuto = findViewById(R.id.cbAuto);

        adapter = new ContactAdapter();
        lvContacts.setAdapter(adapter);
        lvContacts.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                etInput.setText(shown.get(position).number);
                etInput.setSelection(etInput.getText().length());
            }
        });

        etInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });

        btnPaste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pasteFromClipboard(); }
        });
        btnCall.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { placeCall(); }
        });
        btnDefault.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestDefaultDialer(); }
        });
        btnFolder.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickSdFolder(); }
        });

        cbAuto.setChecked(Prefs.isAuto(this));
        cbAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.setAuto(MainActivity.this, checked);
            }
        });

        askPermissions();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        Uri d = i.getData();
        if (d != null && "tel".equals(d.getScheme())) {
            String n = d.getSchemeSpecificPart();
            if (n != null) {
                etInput.setText(Uri.decode(n));
                etInput.setSelection(etInput.getText().length());
            }
        }
    }

    // ---------------------------------------------------------- permissions
    private boolean has(String p) {
        return checkCallingOrSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        if (has(Manifest.permission.READ_CONTACTS)) loadContacts();

        if (Build.VERSION.SDK_INT < 23) return; // Android 5.x এ ইনস্টলের সময়ই পারমিশন পাওয়া যায়

        ArrayList<String> need = new ArrayList<>();
        String[] wanted = {
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE
        };
        for (String p : wanted) if (!has(p)) need.add(p);
        if (Build.VERSION.SDK_INT < 29 && !has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), RC_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == RC_PERMS && has(Manifest.permission.READ_CONTACTS) && all.isEmpty()) {
            loadContacts();
        }
    }

    // ------------------------------------------------------------- contacts
    private void loadContacts() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Contact> tmp = new ArrayList<>();
                Cursor c = null;
                try {
                    c = getContentResolver().query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            new String[]{
                                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                    ContactsContract.CommonDataKinds.Phone.NUMBER},
                            null, null,
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC");
                    if (c != null) {
                        while (c.moveToNext()) {
                            tmp.add(new Contact(c.getString(0), c.getString(1)));
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.close();
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        all.clear();
                        all.addAll(tmp);
                        applyFilter();
                    }
                });
            }
        }).start();
    }

    private void applyFilter() {
        String q = etInput.getText().toString().trim().toLowerCase(Locale.getDefault());
        String qd = digitsOnly(q);
        // 01712345678 পেস্ট করলে +8801712345678 এর সাথে মিলানোর জন্য শেষের ১০ ডিজিট ব্যবহার
        String tail = qd.length() > 10 ? qd.substring(qd.length() - 10) : qd;

        shown.clear();
        if (q.isEmpty()) {
            shown.addAll(all);
        } else {
            for (Contact c : all) {
                if (c.lname.contains(q) || (!tail.isEmpty() && c.digits.contains(tail))) {
                    shown.add(c);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------ clipboard
    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence t = clip.getItemAt(0).coerceToText(this);
                if (t != null && t.length() > 0) {
                    etInput.setText(t.toString().trim());
                    etInput.setSelection(etInput.getText().length());
                    return;
                }
            }
        }
        toast("ক্লিপবোর্ড খালি");
    }

    // ----------------------------------------------------------------- call
    private void placeCall() {
        String num = sanitizeNumber(etInput.getText());
        if (num.isEmpty()) {
            toast("নম্বর দিন বা পেস্ট করুন");
            return;
        }
        if (!has(Manifest.permission.CALL_PHONE)) {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, RC_PERMS);
            }
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", num, null)));
        } catch (SecurityException e) {
            toast("কল করার অনুমতি নেই");
        } catch (ActivityNotFoundException e) {
            toast("কল করা যাচ্ছে না");
        }
    }

    // -------------------------------------------------------- default dialer
    private void requestDefaultDialer() {
        if (Build.VERSION.SDK_INT < 23) {
            toast("ডিফল্ট ডায়ালার সেট করতে Android 6.0 (API 23) বা তার উপরে লাগবে");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                requestDialerRole();
            } else {
                Intent i = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
                i.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
                startActivityForResult(i, RC_DIALER);
            }
        } catch (Exception e) {
            toast("ডিফল্ট ডায়ালার সেট করা যায়নি");
        }
    }

    private void requestDialerRole() {
        RoleManager rm = (RoleManager) getSystemService(ROLE_SERVICE);
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)
                && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), RC_DIALER);
        }
    }

    // ------------------------------------------------------------ SD folder
    private void pickSdFolder() {
        toast("ড্রয়ার থেকে SD কার্ড সিলেক্ট করে ফোল্ডার বেছে নিন, তারপর 'এই ফোল্ডার ব্যবহার করুন' চাপুন");
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(i, RC_SD);
        } catch (ActivityNotFoundException e) {
            toast("ফাইল পিকার পাওয়া যায়নি");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == RC_SD && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                Prefs.setTree(this, u);
                toast("রেকর্ডিং ফোল্ডার সেট হয়েছে");
            } catch (Exception e) {
                toast("ফোল্ডারের অনুমতি সংরক্ষণ করা যায়নি");
            }
        }
        refreshStatus();
    }

    // --------------------------------------------------------------- status
    private void refreshStatus() {
        String dialer;
        if (Build.VERSION.SDK_INT < 23) {
            dialer = "ডিফল্ট ডায়ালার: Android 6.0+ প্রয়োজন";
        } else {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            boolean isDefault = tm != null && getPackageName().equals(tm.getDefaultDialerPackage());
            dialer = isDefault ? "ডিফল্ট ডায়ালার: হ্যাঁ" : "ডিফল্ট ডায়ালার: না";
        }
        Uri t = Prefs.getTree(this);
        String folder = t == null
                ? "ফোল্ডার: SD কার্ডের অ্যাপ-ফোল্ডার (ডিফল্ট)"
                : "ফোল্ডার: " + Uri.decode(String.valueOf(t.getLastPathSegment()));
        tvStatus.setText(dialer + "\n" + folder);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }
}
