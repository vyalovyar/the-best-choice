package com.moviechoice.session.controller;


import com.moviechoice.session.entity.Session;
import com.moviechoice.session.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
//пока побудет для всех, потому что домены разные
@CrossOrigin(origins = "*")
public class SessionController {
    private  final SessionService sessionService;


    @PostMapping
    public ResponseEntity<Map<String, String>> createSession(){
        //создаем сессию
        Session session = sessionService.createSession();
        //собираем jsonchik
        Map<String, String> resp = new HashMap<>();
        resp.put("sessionId", session.getId().toString());
        resp.put("code", session.getCode().toString());
        resp.put("status", session.getStatus().toString());

        //отправляем ответ и статус
        return ResponseEntity.ok(resp);
    }

    //300 раз скажите мне что это кринж, я знаю, добавлю дто и маппер после того, как будет готов mvp
    @GetMapping("/code")
    public ResponseEntity<Map<String, String>> getSessionCode(@PathVariable String code){
        return sessionService.findByCode(code).
                map(session -> {
                    Map<String, String> res = new HashMap<>();
                    res.put("sessionId", session.getId().toString());
                    res.put("code", session.getCode().toString());
                    res.put("status", session.getStatus().toString());
                    return ResponseEntity.ok(res);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
