package de.kutschertec.cameratest;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.TextureView;
import android.view.Window;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_RESTART = 6666;
    private static final int REQUEST_CODE_PERMISSIONS = 6669;

    private final Logger logger = new Logger(this);

    private CameraController cameraController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        if (checkPermissions()) {
            initComponents();
        }

    }

    @Override
    protected void onDestroy() {
        logger.verbose("MainActivity.onDestroy()");
        super.onDestroy();

        logger.debug("Removing lifecyle observers.");
        getLifecycle().removeObserver(cameraController);
        logger.debug("Removing lifecyle observers ... done.");

        logger.verbose("MainActivity.onDestroy() ... done.");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if ((grantResults.length > 0) && (grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                restartApplication();
            }
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private boolean checkPermissions() {
        List<String> requestedPermissions = new ArrayList<>(3);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestedPermissions.add(Manifest.permission.CAMERA);
        }

        if (!requestedPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, requestedPermissions.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
            return false;
        } else {
            return true;
        }
    }

    private void restartApplication() {
        Intent restartIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, REQUEST_CODE_RESTART, restartIntent, PendingIntent.FLAG_CANCEL_CURRENT);
        AlarmManager alarmService = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarmService.set(AlarmManager.RTC, System.currentTimeMillis() + 100, pendingIntent);
        System.exit(0);
    }

    /**
     * Initializes the components.
     */
    private void initComponents() {
        logger.debug("Determine device rotation.");
        TextureView textureView = findViewById(R.id.previewView);
        int deviceRotation = getWindowManager().getDefaultDisplay().getRotation();
        logger.debug("DeviceRotation=" + deviceRotation);
        logger.debug("Determine device rotation ... done.");

        logger.debug("Creating camera2 component.");
        cameraController = new Camera2Component(this, textureView, deviceRotation);
        getLifecycle().addObserver(cameraController);
//        cameraController.setOnWatchDogHandler(this::watchdogTriggered);
        logger.debug("Creating camera2 component ... done.");
    }
}
