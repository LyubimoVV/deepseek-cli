package com.example.deepseek.test;

import com.example.deepseek.db.DatabaseConfig;
import com.example.deepseek.db.MessageDto;
import com.example.deepseek.db.MessageRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;

public class DatabaseTestBase {

    protected Connection connection;

    public void setUp() throws SQLException {
        connection = DatabaseConfig.getConnection();
    }

    protected void createMessages(long sessionId, int count) throws SQLException {
        MessageRepository messageRepo = new MessageRepository();
        for (int i = 1; i <= count; i++) {
            messageRepo.saveMessage(
                sessionId, 
                i % 2 == 0 ? "user" : "assistant",
                "Message " + i, 
                0, 0, 0, 0, 0, 0.0
            );
        }
    }

    protected Connection getConnection() {
        return connection;
    }
}
