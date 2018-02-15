/* ownCloud Android Library is available under MIT license
 *   Copyright (C) 2015 ownCloud Inc.
 *   
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *   
 *   The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 *   
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
 *   EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 *   MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND 
 *   NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS 
 *   BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN 
 *   ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN 
 *   CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *   THE SOFTWARE.
 *
 */

package com.owncloud.android.lib.resources.files;

import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.network.FileRequestEntity;
import com.owncloud.android.lib.common.network.OnDatatransferProgressListener;
import com.owncloud.android.lib.common.network.ProgressiveDataTransferer;
import com.owncloud.android.lib.common.network.WebdavUtils;
import com.owncloud.android.lib.common.operations.OperationCancelledException;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;

import org.apache.commons.httpclient.DefaultHttpMethodRetryHandler;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.httpclient.params.HttpMethodParams;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Remote operation performing the upload of a remote file to the ownCloud server.
 * 
 * @author David A. Velasco
 * @author masensio
 */

public class UploadRemoteFileOperation extends RemoteOperation {

	private static final String TAG = UploadRemoteFileOperation.class.getSimpleName();

	protected static final String OC_TOTAL_LENGTH_HEADER = "OC-Total-Length";
	protected static final String IF_MATCH_HEADER = "If-Match";
    protected static final String OC_X_OC_MTIME_HEADER = "X-OC-Mtime";

	protected String mLocalPath;
	protected String mRemotePath;
	protected String mMimeType;
	protected String mFileLastModifTimestamp;
	protected PutMethod mPutMethod = null;
	protected String mRequiredEtag = null;
	protected long mFileSize = 0;

	protected final AtomicBoolean mCancellationRequested = new AtomicBoolean(false);
	protected Set<OnDatatransferProgressListener> mDataTransferListeners = new HashSet<OnDatatransferProgressListener>();

	protected RequestEntity mEntity = null;

	public UploadRemoteFileOperation(String localPath, String remotePath, String mimeType,
									 String fileLastModifTimestamp) {
		mLocalPath = localPath;
		mRemotePath = remotePath;
		mMimeType = mimeType;
		mFileLastModifTimestamp = fileLastModifTimestamp;
	}

	public UploadRemoteFileOperation(String localPath, String remotePath, String mimeType, String requiredEtag,
									 String fileLastModifTimestamp) {
		this(localPath, remotePath, mimeType, fileLastModifTimestamp);
		mRequiredEtag = requiredEtag;
	}

	public UploadRemoteFileOperation(String localPath, String remotePath, String mimeType, String requiredEtag,
									 String fileLastModifTimestamp, long size) {
		this(localPath, remotePath, mimeType, fileLastModifTimestamp);
		mRequiredEtag = requiredEtag;
		mFileSize = size;
	}

	@Override
	protected RemoteOperationResult run(OwnCloudClient client) {
		RemoteOperationResult result = null;
		DefaultHttpMethodRetryHandler oldRetryHandler =
			(DefaultHttpMethodRetryHandler) client.getParams().getParameter(HttpMethodParams.RETRY_HANDLER);

		try {
			// prevent that uploads are retried automatically by network library
			client.getParams().setParameter(
				HttpMethodParams.RETRY_HANDLER,
				new DefaultHttpMethodRetryHandler(0, false)
			);

			mPutMethod = new PutMethod(client.getWebdavUri() + WebdavUtils.encodePath(mRemotePath));

			if (mCancellationRequested.get()) {
				// the operation was cancelled before getting it's turn to be executed in the queue of uploads
				result = new RemoteOperationResult(new OperationCancelledException());

			} else {
				// perform the upload
				result = uploadFile(client);
			}

		} catch (Exception e) {
			if (mPutMethod != null && mPutMethod.isAborted()) {
				result = new RemoteOperationResult(new OperationCancelledException());

			} else {
				result = new RemoteOperationResult(e);
			}
		} finally {
			// reset previous retry handler
			client.getParams().setParameter(
				HttpMethodParams.RETRY_HANDLER,
				oldRetryHandler
			);
		}
		return result;
	}

	public boolean isSuccess(int status) {
		return ((status == HttpStatus.SC_OK || status == HttpStatus.SC_CREATED ||
                status == HttpStatus.SC_NO_CONTENT));
	}

	protected RemoteOperationResult uploadFile(OwnCloudClient client) throws IOException {
		int status = -1;
		RemoteOperationResult result;

		try {
			File f = new File(mLocalPath);

			if (mFileSize == 0) {
				mFileSize = f.length();
			}
			
			mEntity  = new FileRequestEntity(f, mMimeType);
			synchronized (mDataTransferListeners) {
				((ProgressiveDataTransferer)mEntity)
                        .addDatatransferProgressListeners(mDataTransferListeners);
			}
			if (mRequiredEtag != null && mRequiredEtag.length() > 0) {
				mPutMethod.addRequestHeader(IF_MATCH_HEADER, "\"" + mRequiredEtag + "\"");
			}
			mPutMethod.addRequestHeader(OC_TOTAL_LENGTH_HEADER, String.valueOf(mFileSize));
			mPutMethod.addRequestHeader(OC_X_OC_MTIME_HEADER, mFileLastModifTimestamp);
			mPutMethod.setRequestEntity(mEntity);
			status = client.executeMethod(mPutMethod);

			result = new RemoteOperationResult(isSuccess(status), mPutMethod);

			client.exhaustResponse(mPutMethod.getResponseBodyAsStream());

		} finally {
			mPutMethod.releaseConnection(); // let the connection available for other methods
		}
		return result;
	}

    public Set<OnDatatransferProgressListener> getDataTransferListeners() {
        return mDataTransferListeners;
    }
    
    public void addDatatransferProgressListener (OnDatatransferProgressListener listener) {
        synchronized (mDataTransferListeners) {
            mDataTransferListeners.add(listener);
        }
        if (mEntity != null) {
            ((ProgressiveDataTransferer)mEntity).addDatatransferProgressListener(listener);
        }
    }
    
    public void removeDatatransferProgressListener(OnDatatransferProgressListener listener) {
        synchronized (mDataTransferListeners) {
            mDataTransferListeners.remove(listener);
        }
        if (mEntity != null) {
            ((ProgressiveDataTransferer)mEntity).removeDatatransferProgressListener(listener);
        }
    }
    
    public void cancel() {
        synchronized (mCancellationRequested) {
            mCancellationRequested.set(true);
            if (mPutMethod != null)
                mPutMethod.abort();
        }
    }

}
