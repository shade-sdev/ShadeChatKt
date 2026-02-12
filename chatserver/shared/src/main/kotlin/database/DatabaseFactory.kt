package database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

object DatabaseFactory {

    fun init(runMigrations: Boolean = true) {
        val dataSource = hikari()

        // 1) Run Flyway migrations first
        if (runMigrations) {
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate()
        }

        // 2) Connect Exposed
        Database.connect(dataSource)
    }

    private fun hikari(): HikariDataSource {
        val host = System.getenv("DB_HOST") ?: "localhost"
        val port = System.getenv("DB_PORT") ?: "5432"
        val db = System.getenv("DB_NAME") ?: "chatapp"
        val user = System.getenv("DB_USER") ?: "chatuser"
        val password = System.getenv("DB_PASSWORD") ?: "chatpass"
        val ssl = (System.getenv("DB_SSL") ?: "false").toBoolean()

        val maxPool = (System.getenv("DB_MAX_POOL_SIZE") ?: "10").toInt()
        val minIdle = (System.getenv("DB_MIN_IDLE") ?: "2").toInt()
        val connTimeout = (System.getenv("DB_CONNECTION_TIMEOUT_MS") ?: "30000").toLong()

        val jdbcUrl =
            "jdbc:postgresql://$host:$port/$db?ssl=$ssl&sslmode=${if (ssl) "require" else "disable"}"

        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = password

            this.maximumPoolSize = maxPool
            this.minimumIdle = minIdle
            this.connectionTimeout = connTimeout

            this.driverClassName = "org.postgresql.Driver"

            this.isAutoCommit = false
            this.transactionIsolation = "TRANSACTION_REPEATABLE_READ"

            validate()
        }

        return HikariDataSource(config)
    }
}