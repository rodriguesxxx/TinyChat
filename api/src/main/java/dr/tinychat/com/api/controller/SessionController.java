package dr.tinychat.com.api.controller;

import dr.tinychat.com.api.dto.*;
import dr.tinychat.com.api.security.JwtService;
import dr.tinychat.com.api.service.SessionService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(path = "/api/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
public class SessionController {

	private final SessionService sessionService;
	private final JwtService jwtService;

	public SessionController(SessionService sessionService, JwtService jwtService) {
		this.sessionService = sessionService;
		this.jwtService = jwtService;
	}

	// POST /api/sessions - create a session (header X-User identifies creator)
	@PostMapping
	public ResponseEntity<CreateSessionResponse> createSession(@RequestHeader("X-User") String creator) {
		String sessionId = sessionService.createSession(creator);
		return ResponseEntity.status(HttpStatus.CREATED).body(new CreateSessionResponse(sessionId));
	}

	@PostMapping(path = "/{session}/connect", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ConnectResponse> connect(@PathVariable("session") String session,
												   @RequestBody ConnectSessionRequest req) {
		if (!sessionService.sessionExists(session)) {
			return ResponseEntity.notFound().build();
		}
		String token = jwtService.issueToken(session, req.username());
		return ResponseEntity.ok(new ConnectResponse(token));
	}

	@PostMapping(path = "/{session}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> sendMessage(@PathVariable("session") String session,
											@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
											@RequestBody MessageRequest req) {
		String token = extractBearer(authorization);
		Jws<Claims> claims = jwtService.parseToken(token);
		String tokenSession = claims.getBody().get("session", String.class);
		String username = claims.getBody().getSubject();
		if (!session.equals(tokenSession)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		sessionService.pushMessage(session, username, req.text());
		return ResponseEntity.ok().build();
	}

	@GetMapping(path = "/{session}/messages")
	public ResponseEntity<List<MessageDto>> getMessages(@PathVariable("session") String session) {
		if (!sessionService.sessionExists(session)) return ResponseEntity.notFound().build();
		List<MessageDto> msgs = sessionService.readMessages(session);
		return ResponseEntity.ok(msgs);
	}

	@DeleteMapping(path = "/{session}/destroy")
	public ResponseEntity<Void> destroy(@PathVariable("session") String session,
										@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
		String token = extractBearer(authorization);
		Jws<Claims> claims = jwtService.parseToken(token);
		String username = claims.getBody().getSubject();
		String creator = sessionService.getCreator(session);
		if (creator == null) return ResponseEntity.notFound().build();
		if (!creator.equals(username)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		sessionService.destroySession(session);
		return ResponseEntity.noContent().build();
	}

	private String extractBearer(String header) {
		if (header == null) throw new IllegalArgumentException("Missing Authorization header");
		if (!header.startsWith("Bearer ")) throw new IllegalArgumentException("Invalid Authorization header");
		return header.substring(7);
	}
}

