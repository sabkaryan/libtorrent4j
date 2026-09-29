package org.libtorrent4j;

/**
 * The result of {@link MemoryStoragePool#forgetPiece(TorrentHandle, int)}.
 */
public final class ForgetResult {

    private final int code;
    private final PiecePlace place;

    ForgetResult(int code, PiecePlace place) {
        this.code = code;
        this.place = place;
    }

    /**
     * @return the return code of {@link TorrentHandle}'s forget piece call
     * (0 forgotten, 1 not had, 2 no piece picker, 3 being downloaded or not
     * all blocks written yet, 4 bad index or no metadata, 5 invalid handle),
     * or {@link MemoryStoragePool#NOT_MANAGED}
     */
    public int code() {
        return code;
    }

    /**
     * @return where the bytes of the piece were before it was forgotten
     */
    public PiecePlace place() {
        return place;
    }

    @Override
    public String toString() {
        return "ForgetResult{code=" + code + ", place=" + place + "}";
    }
}
