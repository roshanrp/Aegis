package com.decad3nce.aegis;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import android.accounts.AccountManager;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

import com.actionbarsherlock.app.SherlockActivity;
import com.decad3nce.aegis.Fragments.SMSDataFragment;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.Drive.Files;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.ParentReference;

public class BackupAccountsActivity extends SherlockActivity {
    private static final String TAG = "aeGis";
    public static final String PREFERENCES_BACKUP_CHOSEN_ACCOUNT = "chosen_google_account";
            
    static final int REQUEST_ACCOUNT_PICKER = 1;
    static final int REQUEST_AUTHORIZATION = 2;
    static final int UPLOAD_CALL_LOGS = 3;

    private Context context;
    private static Uri fileUri;
    private static Drive service;
    private boolean folderCreated = false;
    private String address;
    private ContentResolver cr;
    private GoogleAccountCredential credential;
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      context = this;
      Intent intent;
      cr = getContentResolver();
      credential = GoogleAccountCredential.usingOAuth2(this,DriveScopes.DRIVE);
      
      try {
          intent = getIntent();
          
          if (intent.hasExtra("fromReceiver")) {
              address = intent.getStringExtra("fromReceiver");
              Log.i(TAG, "backup intent from receiver");
              recoverData();
          } else {
              Log.i(TAG, "backup intent from elsewhere");
              startActivityForResult(credential.newChooseAccountIntent(), REQUEST_ACCOUNT_PICKER);
          }
          
      } catch(Exception e) {
          recoverData();
      }
    }
    
    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
      switch (requestCode) {
      case REQUEST_ACCOUNT_PICKER:
        if (resultCode == RESULT_OK && data != null && data.getExtras() != null) {
          final String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
          if (accountName != null) {
            Log.i(TAG, "REQUEST ACCOUNT PICKER");
            credential.setSelectedAccountName(accountName);
            service = getDriveService(credential);
            
            SharedPreferences preferences = PreferenceManager
                    .getDefaultSharedPreferences(this);
            
            SharedPreferences.Editor editor = preferences.edit();;
            editor.putString("chosen_google_account", accountName);
            editor.commit();
            getFirstAuthInAsync();
          }
        }
        break;
      case REQUEST_AUTHORIZATION:
        if (resultCode == Activity.RESULT_OK) {
          saveFileToDrive();
        } else {
          startActivityForResult(credential.newChooseAccountIntent(), REQUEST_ACCOUNT_PICKER);
        }
        break;
      }
    }
    
    void getFirstAuthInAsync() {
        AsyncTask task = new AsyncTask() {
            @Override
            protected Object doInBackground(Object... params) {
                Log.i(TAG, "Getting first auth");
                getFirstAuth();
                return null;
            }
        };
        task.execute((Void)null);
    }

    void getFirstAuth() {
        String token;
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(this);
        
        String googleAccount = preferences
                .getString(PREFERENCES_BACKUP_CHOSEN_ACCOUNT, this.getResources().getString(R.string.config_default_google_account));
        
        Log.i(TAG, "Authing with account: " + googleAccount);
        try {
            token = GoogleAuthUtil.getToken(BackupAccountsActivity.this, googleAccount,
                    "Backup data");
        } catch (IOException e) {
            Log.i(TAG, "Exception: " + e.toString());
            e.printStackTrace();
        } catch (GoogleAuthException e) {;
            e.printStackTrace();
            Log.i(TAG, "Exception: " + e.toString());
        }
        if(!isAegisFolderAvailable()) {
            createAegisFolder();
        }
        finish();
    }

    private void createAegisFolder() {
        if(folderCreated){
            return;
        }
        
        Log.i(TAG, "Creating aeGis folder");
        folderCreated = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
              try {               
                  File body = new File();
                  body.setTitle("aeGis Backup");
                  body.setDescription("Backup stored by aeGis");
                  body.setMimeType("application/vnd.google-apps.folder");
                  File file = service.files().insert(body).execute();
                if (file != null) {
                  finish();
                }
              } catch (UserRecoverableAuthIOException e) {
                startActivityForResult(e.getIntent(), REQUEST_AUTHORIZATION);
              } catch (IOException e) {
                e.printStackTrace();
              }
            }
          });
          t.start();
    }
    
    private static boolean isAegisFolderAvailable() {
        
        if(getAegisFolder() == null) {
            return false;
        }
        
        return true;
    }
    
    private static String getAegisFolder() {
        Files.List request = null;
        String folderID = null;
            try {
                request = service.files().list().setQ("mimeType= 'application/vnd.google-apps.folder' and title = 'aeGis Backup' and trashed = false");
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
            
            do {
                    FileList files;
                    try {
                        files = request.execute();
                        for (File file : files.getItems()) {
                            folderID = file.getId();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        return null;
                    }
            } while (request.getPageToken() != null && request.getPageToken().length() > 0);
            
            Log.i(TAG, "FolderID: " + folderID);
        return folderID;
    }

    private void recoverData() {
        Log.i(TAG, "Recovering data");
        
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(this);
        
        String googleAccount = preferences
                .getString(PREFERENCES_BACKUP_CHOSEN_ACCOUNT, this.getResources().getString(R.string.config_default_google_account));
        boolean callLogs = preferences.getBoolean(SMSDataFragment.PREFERENCES_BACKUP_CALL_LOGS, this.getResources().getBoolean(R.bool.config_default_data_backup_call_logs));
        boolean smsLogs = preferences.getBoolean(SMSDataFragment.PREFERENCES_BACKUP_SMS_LOGS, this.getResources().getBoolean(R.bool.config_default_data_backup_sms_logs));
        
        credential.setSelectedAccountName(googleAccount);
        service = getDriveService(credential);
        
        if (callLogs) {
            Log.i(TAG, "Recovering call logs data");
            java.io.File internalFile = getFileStreamPath("call_logs_" + timeStamp + ".txt");
            Uri internal = Uri.fromFile(internalFile);
            fileUri = BackupUtils.getAllCallLogs(cr, internal, this, timeStamp);
            saveFileToDrive();
        }

        if (smsLogs) {
            Log.i(TAG, "Recovering sms logs data");
            java.io.File internalFile = getFileStreamPath("sms_logs_" + timeStamp + ".txt");
            Uri internal = Uri.fromFile(internalFile);
            fileUri = BackupUtils.getSMSLogs(cr, internal, this, timeStamp);
            saveFileToDrive();
        }
        finish();
    }
    
    private void saveFileToDrive() {
        Thread t = new Thread(new Runnable() {
          @Override
          public void run() {
            try {
                if(!isAegisFolderAvailable()) {
                    createAegisFolder();
                }
              
              Log.i(TAG, "Generating new file to upload");
              java.io.File fileContent = new java.io.File(fileUri.getPath());
              FileContent mediaContent = new FileContent("text/plain", fileContent);

              File body = new File();
              body.setTitle(fileContent.getName());
              body.setParents(Arrays.asList(new ParentReference().setId(getAegisFolder())));
              body.setMimeType("text/plain");

              File file = service.files().insert(body, mediaContent).execute();
              if (file != null) {
                Log.i(TAG, "File uploaded successfully");
                Utils.sendSMS(context, address,
                        context.getResources().getString(R.string.util_sendsms_data_recovery_pass) + " "
                                + fileContent.getName());
                deleteFile(fileContent.getName());
                finish();
              }
            } catch (UserRecoverableAuthIOException e) {
              Log.i(TAG, "Exception: " + e.toString());
              startActivityForResult(e.getIntent(), REQUEST_AUTHORIZATION);
            } catch (IOException e) {
              Log.i(TAG, "Exception: " + e.toString());
              e.printStackTrace();
              Utils.sendSMS(context, address,
                      context.getResources().getString(R.string.util_sendsms_data_recovery_fail));
            }
          }
        });
        t.start();
      }

      private Drive getDriveService(GoogleAccountCredential credential) {
        return new Drive.Builder(AndroidHttp.newCompatibleTransport(), new GsonFactory(), credential)
            .build();
      }
}
