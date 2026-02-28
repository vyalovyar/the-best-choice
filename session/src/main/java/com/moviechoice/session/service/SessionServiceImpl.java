package com.moviechoice.session.service;


import com.moviechoice.session.entity.Session;
import com.moviechoice.session.entity.SessionStatus;
import com.moviechoice.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionServiceImpl implements SessionService {

    private final SessionRepository sessionRepository;

    //Метод для создания сессии
    public Session createSession(){
        //генерирую уникальный код с помощью какого-нибудь алгоритма
        String uniqueCode = generateUniqCode();
        //создаю сессию без Lombok-билдера, вручную устанавливая поля
        Session session = new Session();
        session.setCode(uniqueCode);
        session.setStatus(SessionStatus.ACTIVE);
        session.setCreatedAt(ZonedDateTime.now());

        return sessionRepository.save(session);
    }

    //метод для поиска сессии по коду
    public Optional<Session> findByCode(String code){
        return sessionRepository.findByCode(code);
    }
    @Override
    public Optional<Session> getSessionById(UUID sessionId) {
        return sessionRepository.findById(sessionId);
    }

    //Генерерация случайного кода
    //Сделать генерацию поинтересней, типо movi-{код}
    public String generateUniqCode(){
        String code;
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

        //Ура, мне впервые в жизни пригодился do while :)
        do{
            StringBuilder sb= new StringBuilder(10);
            for (int i = 0; i < 6; i++) {
                int index = (int) (Math.random() * characters.length());
                sb.append(characters.charAt(index));
            }
            code = sb.toString();

        } while (sessionRepository.existsByCode(code));
        return code;
    }

}
