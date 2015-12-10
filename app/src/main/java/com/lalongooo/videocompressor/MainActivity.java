package com.lalongooo.videocompressor;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.hole19golf.videocompressor.video.MediaController;
import com.lalongooo.videocompressor.file.FileUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import rx.Observer;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;


public class MainActivity extends Activity {

    private static final int RESULT_CODE_COMPRESS_VIDEO = 3;
    private static final String TAG = "MainActivity";
    private EditText editText;
    private ProgressBar progressBar;
    private File tempFile;
    private TextView progressTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        progressBar = (ProgressBar) findViewById(R.id.progressBar);
        editText = (EditText) findViewById(R.id.editText);
        progressTv = (TextView) findViewById(R.id.progressTv);

        findViewById(R.id.btnSelectVideo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("video/*");
                startActivityForResult(intent, RESULT_CODE_COMPRESS_VIDEO);
            }
        });


    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    protected void onActivityResult(int reqCode, int resCode, Intent data) {
        if (resCode == Activity.RESULT_OK && data != null) {

            Uri uri = data.getData();

            if (reqCode == RESULT_CODE_COMPRESS_VIDEO) {
                if (uri != null) {
                    Cursor cursor = getContentResolver().query(uri, null, null, null, null, null);

                    try {
                        if (cursor != null && cursor.moveToFirst()) {

                            String displayName = cursor.getString(
                                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                            Log.i(TAG, "Display Name: " + displayName);

                            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                            String size = null;
                            if (!cursor.isNull(sizeIndex)) {
                                size = cursor.getString(sizeIndex);
                            } else {
                                size = "Unknown";
                            }
                            Log.i(TAG, "Size: " + size);

                            tempFile = FileUtils.saveTempFile(displayName, this, uri);
                            editText.setText(tempFile.getPath());

                        }
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                }
            }
        }
    }

    private void deleteTempFile() {
        if (tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        deleteTempFile();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        deleteTempFile();
    }

    public void compress(View v) {
        Log.d(TAG, "Start video compression");
        MediaController mediaController = new MediaController();
        String outputPath = tempFile.getParent() + "/" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        String cacheFileOutputPath = Environment.getExternalStorageDirectory() + File.separator + Config.VIDEO_COMPRESSOR_APPLICATION_DIR_NAME +
                Config.VIDEO_COMPRESSOR_COMPRESSED_VIDEOS_DIR + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";

        Log.d(TAG, "Compressed filepath: " + cacheFileOutputPath);
        mediaController.compressVideo(tempFile.getPath(), cacheFileOutputPath);
        progressBar.setVisibility(View.VISIBLE);
        mediaController.getProgressSubject()
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<MediaController.CompressionProgress>() {
                    @Override
                    public void onCompleted() {
                        Log.d(TAG, "Ended compression");
                        clean();
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e(TAG, e.getMessage());
                        clean();
                    }

                    @Override
                    public void onNext(MediaController.CompressionProgress compressionProgress) {
                        float percentage = compressionProgress.getPercentage();
                        progressTv.setText("Progress: " + percentage);
                    }
                });
    }

    private void clean() {
        File file = new File(tempFile.getPath());
        file.delete();

        progressBar.setVisibility(View.GONE);
    }
}