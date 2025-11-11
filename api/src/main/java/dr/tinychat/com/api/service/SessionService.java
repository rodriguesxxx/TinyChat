package dr.tinychat.com.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dr.tinychat.com.api.dto.MessageDto;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.security.SecureRandom;



@Service
public class SessionService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public SessionService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public String createSession(String creator) {
        final int MAX_ATTEMPTS = 1000;
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            String sessionId = generateCode(4);
            String key = keyForCreator(sessionId);
            Boolean success = redisTemplate.opsForValue().setIfAbsent(key, creator);
            if (Boolean.TRUE.equals(success)) {
                return sessionId;
            }
        }
        throw new IllegalStateException("no available session ids");
    }

    public boolean sessionExists(String sessionId) {
        return redisTemplate.hasKey(keyForCreator(sessionId));
    }

    public String getCreator(String sessionId) {
        return redisTemplate.opsForValue().get(keyForCreator(sessionId));
    }

    public void pushMessage(String sessionId, String from, String text) {
        MessageDto dto = new MessageDto(from, text, Instant.now().toEpochMilli());
        try {
            String json = objectMapper.writeValueAsString(dto);
            redisTemplate.opsForList().rightPush(keyForMessages(sessionId), json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public List<MessageDto> readMessages(String sessionId) {
        List<String> raw = redisTemplate.opsForList().range(keyForMessages(sessionId), 0, -1);
        List<MessageDto> out = new ArrayList<>();
        if (raw == null) return out;
        for (String s : raw) {
            try {
                MessageDto dto = objectMapper.readValue(s, MessageDto.class);
                out.add(dto);
            } catch (JsonProcessingException e) {
                // skip malformed
            }
        }
        return out;
    }

    public void destroySession(String sessionId) {
        redisTemplate.delete(keyForCreator(sessionId));
        redisTemplate.delete(keyForMessages(sessionId));
    }

    private String generateCode(int length) {
        final char[] alphabet = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int idx = random.nextInt(alphabet.length);
            sb.append(alphabet[idx]);
        }
        return sb.toString();
    }

    private String keyForCreator(String sessionId) {
        return "session:" + sessionId + ":creator";
    }

    private String keyForMessages(String sessionId) {
        return "session:" + sessionId + ":messages";
    }
}
