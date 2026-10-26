package pe.sistema.web.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Grupo de conexiones a PostgreSQL con el usuario de red app_inventario. */
@Configuration
public class BaseDatosConfig {

    private static final Logger LOG = LoggerFactory.getLogger(BaseDatosConfig.class);

    @Bean
    public DataSource dataSource() {
        ArchivoConexion c = ArchivoConexion.cargar();
        LOG.info("Base de datos: {}", c);
        HikariConfig h = new HikariConfig();
        h.setPoolName("inventario");
        h.setJdbcUrl(c.url());
        h.setUsername(c.usuario());
        h.setPassword(c.clave());
        h.addDataSourceProperty("currentSchema", "inventario,public");
        h.addDataSourceProperty("ApplicationName", "SistemaWeb");
        h.setMaximumPoolSize(10);
        h.setConnectionTimeout(5_000);
        // Arranca aunque PostgreSQL aún no esté listo (por ejemplo, al encender la PC).
        h.setInitializationFailTimeout(-1);
        return new HikariDataSource(h);
    }
}
