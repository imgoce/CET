package com.opensrc.cet.net;

import java.io.IOException;
import java.util.Map;

/**
 * HTTP 요청 1건을 보내는 통로. 실제 앱은 {@link HttpUrlConnectionTransport}를 쓰고,
 * 단위 테스트는 미리 정한 응답을 돌려주는 가짜 구현을 쓴다.
 */
public interface HttpTransport {

    /** 응답 상태 코드와 본문 */
    final class Response {
        public final int status;
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    /**
     * 서버에 연결조차 하지 못해 요청이 나가지 않은 경우.
     * 이때는 같은 요청을 다시 보내도 중복될 위험이 없다 (공용 API 2 재시도 판단, 계약서 §05).
     * 연결 후 응답을 받지 못한 경우는 일반 IOException 이다 (서버 반영 여부를 모름).
     */
    final class NotSentException extends IOException {
        public NotSentException(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }

    /**
     * @param method "GET" 또는 "POST"
     * @param body   POST 본문. GET 이면 null
     */
    Response send(String method, String url, Map<String, String> headers, String body) throws IOException;
}
