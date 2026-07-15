package com.tallybackend.cache;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class TallyPostWriteRefreshService {

    private final TallyCacheSyncService tallyCacheSyncService;

    public TallyPostWriteRefreshService(TallyCacheSyncService tallyCacheSyncService) {
        this.tallyCacheSyncService = tallyCacheSyncService;
    }

    @Async
    public void refreshCacheAsync() {
        try {
            tallyCacheSyncService.runSyncNow();
        } catch (Exception ignored) {
            // The normal scheduler or the next read-triggered sync can recover if this refresh misses.
        }
    }
}
