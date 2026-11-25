package com.opensrc.cet.publicapi;

/**
 * 공용 API 2 업로드 결과 (계약서 §05, EX-4). 상태에 따라 앱이 할 일이 다르다.
 *
 * <table>
 *   <tr><th>status</th><th>뜻</th><th>앱이 할 일</th></tr>
 *   <tr><td>SUCCESS</td><td>공용 서버 저장 성공</td><td>publicId 를 로컬에 저장한 뒤 commitCollection(UPLOADED, publicId)</td></tr>
 *   <tr><td>INPUT_ERROR</td><td>HTTP 400, 입력 오류</td><td>error 를 표시. 같은 값으로 다시 보내지 않는다</td></tr>
 *   <tr><td>NOT_SENT</td><td>연결 실패로 요청이 나가지 않음</td><td>5초 간격 최대 3회 재시도 후 기기 대기열에 보관</td></tr>
 *   <tr><td>UNKNOWN</td><td>보냈지만 응답을 못 받음 (저장 여부 모름)</td><td>PUBLIC_UNKNOWN 으로 보류. 자동 재전송·보상 없음</td></tr>
 * </table>
 */
public final class UploadResult {
    public enum Status { SUCCESS, INPUT_ERROR, NOT_SENT, UNKNOWN }

    public final Status status;
    /** SUCCESS 일 때 공용 서버가 준 양의 정수 id, 그 밖에는 null */
    public final Long publicId;
    /** 실패 사유 (SUCCESS 이면 null) */
    public final String error;

    private UploadResult(Status status, Long publicId, String error) {
        this.status = status;
        this.publicId = publicId;
        this.error = error;
    }

    static UploadResult success(long publicId) {
        return new UploadResult(Status.SUCCESS, publicId, null);
    }

    static UploadResult inputError(String error) {
        return new UploadResult(Status.INPUT_ERROR, null, error);
    }

    static UploadResult notSent(String error) {
        return new UploadResult(Status.NOT_SENT, null, error);
    }

    static UploadResult unknown(String error) {
        return new UploadResult(Status.UNKNOWN, null, error);
    }

    @Override
    public String toString() {
        return "UploadResult{" + status + ", publicId=" + publicId + ", error=" + error + "}";
    }
}
