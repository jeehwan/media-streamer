[![](https://jitpack.io/v/jeehwan/media-streamer.svg)](https://jitpack.io/#jeehwan/media-streamer)
# 2019 TECH CONCERT MOBILE - 20분만에 라이브 송출 앱 만들기

RTMP로 live 방송을 하기 위한 라이브러리<br/>
LiveAppIn20Mins 샘플 앱에서 사용

안드로이드의 MediaRecord와 유사한 사용성을 제공함

## Prerequisites
- Min SDK 21

## 사용 방법
프로젝트의 build.gradle에 다음과 같이 repository를 추가합니다.
```groovy
allprojects {
	repositories {
		...
		maven { url 'https://jitpack.io' }
	}
}
```

다음과 같이 dependency를 추가합니다.
```groovy
dependencies {
        implementation 'com.github.jeehwan:media-streamer:0.0.1'
}
```

## rtmp-rtsp-stream-client-java
https://github.com/pedroSG94/rtmp-rtsp-stream-client-java 코드를 기반으로 작성됨<br/>
rtmp-rtsp-stream-client-java/rtmp 코드를 포함함
