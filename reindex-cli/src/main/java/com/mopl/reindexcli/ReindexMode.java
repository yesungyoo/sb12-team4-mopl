package com.mopl.reindexcli;

import java.util.List;
import org.springframework.boot.ApplicationArguments;

public enum ReindexMode {
    SYNC_DATA,
    EMBEDDING_BACKFILL,
    DLT_REPLAY;

    private static final String MODE_OPTION = "mopl.reindex.mode";

    public static ReindexMode from(ApplicationArguments arguments) {
        List<String> modes = arguments.getOptionValues(MODE_OPTION);
        if (modes == null || modes.size() != 1 || modes.get(0).isBlank()) {
            throw new IllegalArgumentException(
                    "실행 모드가 필요합니다. --mopl.reindex.mode=SYNC_DATA, EMBEDDING_BACKFILL 또는 DLT_REPLAY"
            );
        }

        try {
            return valueOf(modes.get(0));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "지원하지 않는 재색인 모드입니다: " + modes.get(0)
                            + ". 허용 모드: SYNC_DATA, EMBEDDING_BACKFILL, DLT_REPLAY",
                    exception
            );
        }
    }
}
