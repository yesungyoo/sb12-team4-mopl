package com.mopl.auth.jwt;

/** 자료에 명시되지 않아 임의로 정한 쿠키 이름입니다. 프론트와 합의 후 확정해주세요. */
public class AuthCookies {

    public static final String ACCESS_TOKEN = "ACCESS_TOKEN";
    public static final String REFRESH_TOKEN = "REFRESH_TOKEN";

    private AuthCookies() {
    }
}