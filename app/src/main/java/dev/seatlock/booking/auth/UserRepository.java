package dev.seatlock.booking.auth;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class UserRepository {

	private final JdbcClient jdbc;

	UserRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Throws {@link UsernameTakenException} when the unique username constraint rejects the row. */
	void insert(User user) {
		try {
			jdbc.sql("insert into users (id, username, password_hash, created_at) values (?, ?, ?, ?)")
					.params(user.id(), user.username(), user.passwordHash(), user.createdAt().atOffset(ZoneOffset.UTC))
					.update();
		}
		catch (DuplicateKeyException e) {
			throw new UsernameTakenException(user.username());
		}
	}

	Optional<User> findByUsername(String username) {
		return jdbc.sql("select id, username, password_hash, created_at from users where username = ?")
				.param(username)
				.query((rs, i) -> new User(rs.getObject("id", UUID.class), rs.getString("username"),
						rs.getString("password_hash"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
				.optional();
	}

}
