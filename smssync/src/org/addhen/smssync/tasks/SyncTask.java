
package org.addhen.smssync.tasks;

import static org.addhen.smssync.tasks.state.SyncState.CANCELED_SYNC;
import static org.addhen.smssync.tasks.state.SyncState.ERROR;
import static org.addhen.smssync.tasks.state.SyncState.FINISHED_SYNC;
import static org.addhen.smssync.tasks.state.SyncState.INITIAL;
import static org.addhen.smssync.tasks.state.SyncState.SYNC;

import java.util.Locale;

import org.addhen.smssync.MainApplication;
import org.addhen.smssync.MessageType;
import org.addhen.smssync.exceptions.ConnectivityException;
import org.addhen.smssync.models.MessagesModel;
import org.addhen.smssync.models.SyncUrlModel;
import org.addhen.smssync.services.SyncPendingMessagesService;
import org.addhen.smssync.tasks.state.MessageSyncState;
import org.addhen.smssync.tasks.state.SyncState;
import org.addhen.smssync.util.Logger;
import org.addhen.smssync.util.MessageSyncUtil;
import org.addhen.smssync.util.ServicesConstants;

import android.os.AsyncTask;

import com.squareup.otto.Subscribe;

/**
 * Provide a background service for synchronizing huge messages
 */
public class SyncTask extends AsyncTask<SyncConfig, MessageSyncState, MessageSyncState> {

    private final SyncPendingMessagesService mService;

    private final static String TAG = SyncTask.class.getSimpleName();

    private final MessagesModel messagesModel;

    private SyncUrlModel model;

    private MessageType messageType;

    private int itemsToSync;

    public SyncTask(SyncPendingMessagesService service, MessageType messageType) {
        this.mService = service;
        this.messageType = messageType;
        this.messagesModel = new MessagesModel();
        this.model = new SyncUrlModel();
    }

    @Override
    protected MessageSyncState doInBackground(SyncConfig... params) {
        final SyncConfig config = params[0];
        if (config.skip) {
            Logger.log(TAG, "Sync skipped");

            return new MessageSyncState(FINISHED_SYNC, 0, 0, SyncType.MANUAL, null, null);
        }

        try {
            mService.acquireLocks();

            return sync(config);

        } catch (ConnectivityException e) {
            Logger.log(TAG, "No internet connection");
            return transition(ERROR, e);

        } finally {
            mService.releaseLocks();
        }
    }

    @Override
    protected void onPreExecute() {
        MainApplication.bus.register(this);
    }

    @Subscribe
    public void taskCanceled(TaskCanceled canceled) {
        cancel(false);
    }

    @Override
    protected void onPostExecute(MessageSyncState result) {
        if (result != null) {
            post(result);
        }
        MainApplication.bus.unregister(this);
    }

    @Override
    protected void onCancelled() {
        post(transition(CANCELED_SYNC, null));
        MainApplication.bus.unregister(this);
    }

    private void post(MessageSyncState state) {
        MainApplication.bus.post(state);
    }

    private MessageSyncState transition(SyncState state, Exception exception) {
        return mService.getState().transition(state, exception);
    }

    @Override
    protected void onProgressUpdate(MessageSyncState... progress) {
        if (progress != null && progress.length > 0 && !isCancelled()) {
            post(progress[0]);
        }
    }

    private MessageSyncState sync(SyncConfig config) {
        Logger.log(TAG, "syncToWeb(): push pending messages to the Sync URL");
        publishState(INITIAL);

        int syncdItems = 0;
        switch (messageType) {
            case PENDING:
                syncdItems = syncPending(config);
                break;
            case TASK:
                syncTask(config);
                break;
        }

        if (syncdItems == 0) {

            Logger.log(TAG, "Nothing to do.");
            return transition(FINISHED_SYNC, null);
        }

        return new MessageSyncState(FINISHED_SYNC,
                syncdItems,
                itemsToSync,
                config.syncType, messageType, null);

    }

    private int syncPending(SyncConfig config) {
        // sync pending messages
        int syncdItems = 0;
        if (messagesModel.totalMessages() > 0) {
            itemsToSync = messagesModel.totalMessages();
            Logger.log(TAG,
                    String.format(Locale.ENGLISH, "Starting to sync (%d messages)", itemsToSync));
            for (SyncUrlModel syncUrl : model
                    .loadByStatus(ServicesConstants.ACTIVE_SYNC_URL)) {
                try {
                    Thread.sleep(1000);
                    if (0 == new MessageSyncUtil(mService.getApplicationContext(), syncUrl.getUrl())
                            .syncToWeb(config.messageUuid)) {

                        syncdItems ++;
                    }
                    publishProgress(new MessageSyncState(SYNC, syncdItems, itemsToSync,
                            config.syncType, messageType, null));
                } catch (InterruptedException e) {
                    Logger.log(TAG, "Thread interrupted");
                }
            }

        }
        return syncdItems;
    }

    private void syncTask(SyncConfig config) {
        Logger.log(TAG, "checkTaskService: check if a task has been enabled.");
        // Perform a task
        // get enabled Sync URL
        for (SyncUrlModel syncUrl : model
                .loadByStatus(ServicesConstants.ACTIVE_SYNC_URL)) {
            new MessageSyncUtil(mService.getApplicationContext(), syncUrl.getUrl())
                    .performTask(syncUrl.getSecret());
        }
    }

    private void publishState(SyncState state) {
        publishState(state, null);
    }

    private void publishState(SyncState state, Exception e) {
        publishProgress(mService.getState().transition(state, e));
    }
}
