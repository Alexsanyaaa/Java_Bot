package mentor;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ImageInputTest {
    private static void check(boolean ok, String why) {
        org.assertj.core.api.Assertions.assertThat(ok).as(why).isTrue();
    }
    private static Map<String,Object> update(long id, Object media, boolean document, String caption, int owner) {
        var message = new LinkedHashMap<String,Object>();
        message.put("message_id", 500 + id);
        message.put("chat",Map.of("id",owner,"type","private"));
        message.put("from",Map.of("id",owner));
        message.put(document ? "document" : "photo",media);
        if (caption != null) message.put("caption",caption);
        return Map.of("update_id",id,"message",message);
    }
    @org.junit.jupiter.api.Test
    void screenshotsRemainSupportedAfterMovingToTheAuthoritativeRepository() throws Exception {
        var json=JsonMapper.builder().build();
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j7f8AAAAASUVORK5CYII=");
        var updates=new CopyOnWriteArrayList<Map<String,Object>>();
        var texts=new CopyOnWriteArrayList<String>();
        var calls=new CopyOnWriteArrayList<JsonNode>();
        var reactions=new CopyOnWriteArrayList<JsonNode>();
        var visionBroken=new AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            String path=exchange.getRequestURI().getPath();
            byte[] result;
            if (path.endsWith("/shot.png")) {
                result=png;
            } else {
                JsonNode body=json.readTree(exchange.getRequestBody());
                if(path.endsWith("/getUpdates")) {
                    long offset=body.path("offset").asLong();
                    result=json.writeValueAsBytes(Map.of("ok",true,"result",updates.stream()
                        .filter(item -> (long)item.get("update_id")>=offset).toList()));
                } else if(path.endsWith("/getFile")) {
                    result=json.writeValueAsBytes(Map.of("ok",true,"result",Map.of("file_path","shot.png")));
                } else if(path.endsWith("/chat/completions")) {
                    calls.add(body);
                    JsonNode content=body.path("messages").path(0).path("content");
                    if(content.isArray()) {
                        check(content.get(1).path("image_url").path("url").asText().startsWith("data:image/png;base64,"),"No inline picture");
                        if(visionBroken.get()) {
                            exchange.sendResponseHeaders(503,-1);
                            exchange.close();
                            return;
                        }
                    }
                    result=json.writeValueAsBytes(Map.of("model","gemini-mock","choices",List.of(Map.of("finish_reason","stop","message",
                        Map.of("content",content.isArray() ? "На экране Java-код: int count = 4827;" : "[[react:🔥]] Вижу число 4827 в коде.")))));
                } else {
                    if(path.endsWith("/setMessageReaction")) reactions.add(body);
                    if(path.endsWith("/sendMessage")) texts.add(body.path("text").asText());
                    result=json.writeValueAsBytes(Map.of("ok",true,"result",Map.of("message_id",123)));
                }
            }
            exchange.sendResponseHeaders(200,result.length);
            try(var out=exchange.getResponseBody()) { out.write(result); }
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var model=new MentorModel(json,base+"/llm","gemini:gemini-mock","");
            for(var value:Map.of("geminiKey","fake","geminiBase",base+"/llm").entrySet()) {
                var field=MentorModel.class.getDeclaredField(value.getKey());
                field.setAccessible(true);
                field.set(model,value.getValue());
            }
            var jdbc=new Database("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
            var store=new StateStore(jdbc,json);
            var bot=new MentorBot(store,new Planner(),new TelegramApi(json,base+"/telegram","fake",123),model,Clock.systemUTC(),"","","");
            var media=List.of(Map.of("file_id","picture","file_size",png.length,"width",800,"height",400));
            updates.add(update(1,media,false,"Объясни строку на скриншоте",123));
            bot.poll();
            check(texts.getLast().contains("4827"),"No reply after picture");
            check(reactions.size()==1 && reactions.getFirst().path("message_id").asLong()==501,"Reaction targets original photo");
            check(calls.size()==2,"Expected image reading and chat");
            check(calls.getLast().toString().contains("Объясни строку на скриншоте"),"Caption lost");
            check(store.read(s -> s.conversation.getLast().question()).contains("int count = 4827"),"Picture content not remembered");
            updates.add(update(2,media,false,null,123));
            bot.poll();
            check(texts.getLast().contains("4827"),"Bare image ignored");
            var document=Map.of("file_id","picture","mime_type","image/png","file_size",png.length);
            updates.add(update(3,document,true,null,123));
            bot.poll();
            check(texts.getLast().contains("4827"),"Image document ignored");
            int before=calls.size();
            updates.add(update(4,media,false,null,999));
            bot.poll();
            check(calls.size()==before,"Read unauthorized image");
            updates.add(update(5,Map.of("file_id","picture","mime_type","image/png","file_size",3*1024*1024),true,null,123));
            bot.poll();
            check(texts.getLast().contains("2 МиБ"),"No oversized warning");
            visionBroken.set(true);
            updates.add(update(6,media,false,null,123));
            bot.poll();
            check(texts.getLast().contains("прочитать её сейчас не удалось"),"Vision error was silent");
            check(store.read(s -> s.updateOffset)==7,"Failed image stuck the queue");
            updates.add(Map.of("update_id",7L,"message",Map.of("from",Map.of("id",123),"chat",Map.of("id",123,"type","private"),"text","/help")));
            bot.poll();
            check(texts.getLast().contains("/submit"),"Bot did not continue after image error");
        } finally {
            server.stop(0);
        }
    }
}
