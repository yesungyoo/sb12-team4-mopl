package com.mopl.core.common.enums;

public enum NotificationType {
    FOLLOW,
    DIRECT_MESSAGE,
    PLAYLIST_SUBSCRIBED,
    PLAYLIST_CONTENT_ADDED,
    ROLE_CHANGED,
    FOLLOWING_PLAYLIST_CREATED,   // 팔로우한 사용자가 새 플레이리스트 생성
    FOLLOWING_REVIEW_CREATED,     // 팔로우한 사용자가 새 리뷰 작성
    FOLLOWING_WATCH_STARTED       // 팔로우한 사용자가 같이보기 시작
}