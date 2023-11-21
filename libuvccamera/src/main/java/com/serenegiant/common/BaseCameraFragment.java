/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.serenegiant.common;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Fragment;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import android.util.Log;
import android.widget.Toast;

import com.serenegiant.dialog.MessageDialogFragment;
import com.serenegiant.utils.BuildCheck;
import com.serenegiant.utils.HandlerThreadHandler;
import com.serenegiant.utils.PermissionCheck;

/**
 * Created by saki on 2016/11/19.
 *
 */
public class BaseCameraFragment extends Fragment implements MessageDialogFragment.MessageDialogListener {

	private static boolean DEBUG = false;   // FIXME 在實際運行時應設置為false
	private static final String TAG = BaseCameraFragment.class.getSimpleName();

	/** 用於UI操作的處理器 */
	private final Handler mUIHandler = new Handler(Looper.getMainLooper());
	private final Thread mUiThread = mUIHandler.getLooper().getThread();
	/** 用於在工作線程上處理的Handler */
	private Handler mWorkerHandler;
	private long mWorkerThreadID = -1;

	public BaseCameraFragment() {
		super();
	}

	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		// 生成工作線程
		if (mWorkerHandler == null) {
			mWorkerHandler = HandlerThreadHandler.createHandler(TAG);
			mWorkerThreadID = mWorkerHandler.getLooper().getThread().getId();
		}
	}

	@Override
	public void onPause() {
		clearToast();
		super.onPause();
	}

	@Override
	public synchronized void onDestroy() {
		// 銷毀工作線程
		if (mWorkerHandler != null) {
			try {
				mWorkerHandler.getLooper().quit();
			} catch (final Exception e) {
				//
			}
			mWorkerHandler = null;
		}
		super.onDestroy();
	}

//================================================================================
	/**
	 * 在UI線程中執行Runnable的輔助方法
	 * @param task
	 * @param duration
	 */
	public final void runOnUiThread(final Runnable task, final long duration) {
		if (task == null) return;
		mUIHandler.removeCallbacks(task);
		if ((duration > 0) || Thread.currentThread() != mUiThread) {
			mUIHandler.postDelayed(task, duration);
		} else {
			try {
				task.run();
			} catch (final Exception e) {
				Log.w(TAG, e);
			}
		}
	}

	/**
	 * 如果指定的Runnable正在UI線程上等待執行，則取消等待
	 * @param task
	 */
	public final void removeFromUiThread(final Runnable task) {
		if (task == null) return;
		mUIHandler.removeCallbacks(task);
	}

	/**
	 * 在工作線程上執行指定的Runnable
	 * 如果存在未執行的同一Runnable，則會取消（只執行最後指定的）
	 * @param task
	 * @param delayMillis
	 */
	protected final synchronized void queueEvent(final Runnable task, final long delayMillis) {
		if ((task == null) || (mWorkerHandler == null)) return;
		try {
			mWorkerHandler.removeCallbacks(task);
			if (delayMillis > 0) {
				mWorkerHandler.postDelayed(task, delayMillis);
			} else if (mWorkerThreadID == Thread.currentThread().getId()) {
				task.run();
			} else {
				mWorkerHandler.post(task);
			}
		} catch (final Exception e) {
			// 忽略
		}
	}

	/**
	 * 如果指定的Runnable預計在工作線程上執行，則取消之
	 * @param task
	 */
	protected final synchronized void removeEvent(final Runnable task) {
		if (task == null) return;
		try {
			mWorkerHandler.removeCallbacks(task);
		} catch (final Exception e) {
			// 忽略
		}
	}

	//================================================================================
	private Toast mToast;
	/**
	 * 透過Toast顯示訊息
	 * @param msg
	 */
	protected void showToast(@StringRes final int msg, final Object... args) {
		removeFromUiThread(mShowToastTask);
		mShowToastTask = new ShowToastTask(msg, args);
		runOnUiThread(mShowToastTask, 0);
	}

	/**
	 * 如果Toast正在顯示，則取消之
	 */
	protected void clearToast() {
		removeFromUiThread(mShowToastTask);
		mShowToastTask = null;
		try {
			if (mToast != null) {
				mToast.cancel();
				mToast = null;
			}
		} catch (final Exception e) {
			// 忽略
		}
	}

	private ShowToastTask mShowToastTask;
	private final class ShowToastTask implements Runnable {
		final int msg;
		final Object args;
		private ShowToastTask(@StringRes final int msg, final Object... args) {
			this.msg = msg;
			this.args = args;
		}

		@Override
		public void run() {
			try {
				if (mToast != null) {
					mToast.cancel();
					mToast = null;
				}
				final String _msg = args != null ? getString(msg, args) : getString(msg);
				mToast = Toast.makeText(getActivity(), _msg, Toast.LENGTH_SHORT);
				mToast.show();
			} catch (final Exception e) {
				// 忽略
			}
		}
	}

//================================================================================
	/**
	 * MessageDialogFragment訊息對話框的回調監聽器
	 * @param dialog
	 * @param requestCode
	 * @param permissions
	 * @param result
	 */
	@SuppressLint("NewApi")
	@Override
	public void onMessageDialogResult(final MessageDialogFragment dialog, final int requestCode, final String[] permissions, final boolean result) {
		if (result) {
			// 在訊息對話框中點選OK時請求權限
			if (BuildCheck.isMarshmallow()) {
				requestPermissions(permissions, requestCode);
				return;
			}
		}
		// 在訊息對話框中點選取消或非Android 6時，自行檢查並調用#checkPermissionResult
		for (final String permission : permissions) {
			checkPermissionResult(requestCode, permission, PermissionCheck.hasPermission(getActivity(), permission));
		}
	}

	/**
	 * 接收權限請求的結果
	 * @param requestCode
	 * @param permissions
	 * @param grantResults
	 */
	@Override
	public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions, @NonNull final int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);  // 雖然沒有做什麼，但還是調用一下
		final int n = Math.min(permissions.length, grantResults.length);
		for (int i = 0; i < n; i++) {
			checkPermissionResult(requestCode, permissions[i], grantResults[i] == PackageManager.PERMISSION_GRANTED);
		}
	}

	/**
	 * 檢查權限請求的結果
	 * 若未獲得權限，則透過Toast顯示訊息
	 * @param requestCode
	 * @param permission
	 * @param result
	 */
	protected void checkPermissionResult(final int requestCode, final String permission, final boolean result) {
		// 若無權限，則顯示訊息
		if (!result && (permission != null)) {
			if (Manifest.permission.RECORD_AUDIO.equals(permission)) {
				showToast(com.serenegiant.common.R.string.permission_audio);
			}
			if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permission)) {
				showToast(com.serenegiant.common.R.string.permission_ext_storage);
			}
			if (Manifest.permission.INTERNET.equals(permission)) {
				showToast(com.serenegiant.common.R.string.permission_network);
			}
		}
	}

	// 動態權限請求的請求代碼
	protected static final int REQUEST_PERMISSION_WRITE_EXTERNAL_STORAGE = 0x12345;
	protected static final int REQUEST_PERMISSION_AUDIO_RECORDING = 0x234567;
	protected static final int REQUEST_PERMISSION_NETWORK = 0x345678;
	protected static final int REQUEST_PERMISSION_CAMERA = 0x537642;

	/**
	 * 檢查是否有外部儲存設備寫入權限
	 * 若無則顯示說明對話框
	 * @return true 若有外部儲存設備寫入權限
	 */
	protected boolean checkPermissionWriteExternalStorage() {
		if (!PermissionCheck.hasWriteExternalStorage(getActivity())) {
			MessageDialogFragment.showDialog(this, REQUEST_PERMISSION_WRITE_EXTERNAL_STORAGE,
					com.serenegiant.common.R.string.permission_title, com.serenegiant.common.R.string.permission_ext_storage_request,
					new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE});
			return false;
		}
		return true;
	}

	/**
	 * 檢查是否有錄音權限
	 * 若無則顯示說明對話框
	 * @return true 若有錄音權限
	 */
	protected boolean checkPermissionAudio() {
		if (!PermissionCheck.hasAudio(getActivity())) {
			MessageDialogFragment.showDialog(this, REQUEST_PERMISSION_AUDIO_RECORDING,
					com.serenegiant.common.R.string.permission_title, com.serenegiant.common.R.string.permission_audio_recording_request,
					new String[]{Manifest.permission.RECORD_AUDIO});
			return false;
		}
		return true;
	}

	/**
	 * 檢查是否有網路存取權限
	 * 若無則顯示說明對話框
	 * @return true 若有網路存取權限
	 */
	protected boolean checkPermissionNetwork() {
		if (!PermissionCheck.hasNetwork(getActivity())) {
			MessageDialogFragment.showDialog(this, REQUEST_PERMISSION_NETWORK,
					com.serenegiant.common.R.string.permission_title, com.serenegiant.common.R.string.permission_network_request,
					new String[]{Manifest.permission.INTERNET});
			return false;
		}
		return true;
	}

	/**
	 * 檢查是否有攝像頭存取權限
	 * 若無則顯示說明對話框
	 * @return true 若有攝像頭存取權限
	 */
	protected boolean checkPermissionCamera() {
		if (!PermissionCheck.hasCamera(getActivity())) {
			MessageDialogFragment.showDialog(this, REQUEST_PERMISSION_CAMERA,
					com.serenegiant.common.R.string.permission_title, com.serenegiant.common.R.string.permission_camera_request,
					new String[]{Manifest.permission.CAMERA});
			return false;
		}
		return true;
	}
}
