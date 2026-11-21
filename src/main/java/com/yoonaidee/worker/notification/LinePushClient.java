package com.yoonaidee.worker.notification;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LinePushClient {
 private final RestClient client=RestClient.builder().baseUrl("https://api.line.me").build();
 private final String token;
 public LinePushClient(@Value("${line.channel-access-token:}") String token){this.token=token;}
 public void send(String lineUserId,String preview,String resultUrl,String retryKey){
  if(token.isBlank())throw new IllegalStateException("LINE_CHANNEL_ACCESS_TOKEN missing");
  if(resultUrl==null||!resultUrl.startsWith("https://"))throw new IllegalArgumentException("HTTPS LIFF URL required");
  var message=Map.of("type","template","altText","วิเคราะห์ทำเลเสร็จแล้ว",
    "template",Map.of("type","buttons","text",preview.length()>155?preview.substring(0,155):preview,
      "actions",List.of(Map.of("type","uri","label","ดูผลวิเคราะห์","uri",resultUrl))));
  client.post().uri("/v2/bot/message/push")
    .header("Authorization","Bearer "+token)
    .header("X-Line-Retry-Key",retryKey)
    .body(Map.of("to",lineUserId,"messages",List.of(message)))
    .retrieve().toBodilessEntity();
 }
}
