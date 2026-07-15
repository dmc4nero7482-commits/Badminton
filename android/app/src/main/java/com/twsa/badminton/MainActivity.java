package com.twsa.badminton;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import androidx.core.content.FileProvider;
import com.getcapacitor.BridgeActivity;
import java.io.File;

public class MainActivity extends BridgeActivity {

    private String pendingUrl = null;
    private String pendingFilename = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getBridge().getWebView().addJavascriptInterface(new UpdateBridge(), "AndroidUpdate");
        getBridge().getWebView().addJavascriptInterface(new ScoreBoardBridge(), "AndroidScoreboard");
    }

    @Override
    public void onResume() {
        super.onResume();
        if (pendingUrl != null && canInstall()) {
            String url = pendingUrl;
            String filename = pendingFilename;
            pendingUrl = null;
            pendingFilename = null;
            new UpdateBridge().downloadAndInstall(url, filename);
        }
    }

    private boolean canInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    class ScoreBoardBridge {
        @JavascriptInterface
        public void openScoreboard(String teamA, String teamB) {
            try {
                Uri uri = Uri.parse("twsa-scoreboard://start?teamA=" + Uri.encode(teamA) + "&teamB=" + Uri.encode(teamB));
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                final String a = teamA.replace("'", "\\'");
                final String b = teamB.replace("'", "\\'");
                runOnUiThread(() ->
                    getBridge().getWebView().evaluateJavascript(
                        "openScoreboardWeb('" + a + "','" + b + "')", null));
            }
        }
    }

    class UpdateBridge {
        @JavascriptInterface
        public void downloadAndInstall(String url, String filename) {
            File destFile = new File(getExternalFilesDir(null), filename);

            debug("canInstall=" + canInstall() + " fileExists=" + destFile.exists());

            if (destFile.exists() && canInstall()) {
                if (isValidUpdate(destFile)) {
                    debug("已有有效更新檔且有權限，直接安裝");
                    runOnUiThread(() -> installApk(destFile.getAbsolutePath()));
                    return;
                }
                debug("快取檔案無效或非新版，刪除後重新下載");
                destFile.delete();
            }

            if (!canInstall()) {
                debug("無安裝權限，導向設定");
                pendingUrl = url;
                pendingFilename = filename;
                runOnUiThread(() -> {
                    android.widget.Toast.makeText(MainActivity.this,
                        "請允許「從此來源安裝應用程式」，完成後再點一次更新",
                        android.widget.Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                });
                return;
            }

            if (destFile.exists()) destFile.delete();
            debug("開始下載...");

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("正在下載更新");
            request.setDescription("羽球排場");
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_HIDDEN);
            request.setDestinationUri(Uri.fromFile(destFile));
            request.setMimeType("application/vnd.android.package-archive");

            long downloadId = dm.enqueue(request);
            startProgressPolling(dm, downloadId, destFile.getAbsolutePath());
        }

        // 檢查 APK 檔案完整可解析，且版本比目前安裝的新，避免安裝到壞檔或舊檔
        private boolean isValidUpdate(File f) {
            try {
                android.content.pm.PackageInfo apk =
                    getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (apk == null) return false;
                android.content.pm.PackageInfo cur =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
                long apkVer = Build.VERSION.SDK_INT >= 28 ? apk.getLongVersionCode() : apk.versionCode;
                long curVer = Build.VERSION.SDK_INT >= 28 ? cur.getLongVersionCode() : cur.versionCode;
                return apkVer > curVer;
            } catch (Exception e) {
                return false;
            }
        }

        private void debug(String msg) {
            android.util.Log.d("APK_INSTALL", msg);
            runOnUiThread(() ->
                getBridge().getWebView().evaluateJavascript(
                    "onDebugMsg('" + msg.replace("'", "\\'") + "')", null));
        }

        private void startProgressPolling(DownloadManager dm, long downloadId, String filePath) {
            new Thread(() -> {
                boolean running = true;
                while (running) {
                    DownloadManager.Query query = new DownloadManager.Query();
                    query.setFilterById(downloadId);
                    Cursor cursor = dm.query(query);
                    if (cursor != null && cursor.moveToFirst()) {
                        int status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        long downloaded = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        cursor.close();

                        if (total > 0) {
                            int percent = (int) (downloaded * 100 / total);
                            runOnUiThread(() ->
                                getBridge().getWebView().evaluateJavascript(
                                    "onDownloadProgress(" + percent + ")", null));
                        }

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            running = false;
                            // 注意：不可呼叫 dm.remove()，它會把剛下載完的檔案一併刪除，導致安裝失敗
                            debug("STATUS_SUCCESSFUL，呼叫 installApk");
                            runOnUiThread(() -> installApk(filePath));
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            debug("下載失敗");
                            running = false;
                        }
                    } else {
                        if (cursor != null) cursor.close();
                        running = false;
                    }

                    try { Thread.sleep(300); } catch (InterruptedException e) { break; }
                }
            }).start();
        }

        private void installApk(String filePath) {
            debug("installApk 開始");
            try {
                File apkFile = new File(filePath);
                debug("檔案存在:" + apkFile.exists() + " 大小:" + apkFile.length());
                Uri uri = FileProvider.getUriForFile(
                    MainActivity.this,
                    getPackageName() + ".fileprovider",
                    apkFile);
                debug("URI:" + uri.toString());
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
                debug("startActivity 完成");
            } catch (Exception e) {
                debug("錯誤:" + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
    }
}
