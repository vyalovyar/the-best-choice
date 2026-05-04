package com.moviechoice.session.repository;

import com.moviechoice.session.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<Session, UUID> {
    //Ищем ссессию по уникальному коду
    Optional<Session> findByCode(String code);

    //Проверка на наличие сессии с таким кодом
    boolean existsByCode(String code);
}
