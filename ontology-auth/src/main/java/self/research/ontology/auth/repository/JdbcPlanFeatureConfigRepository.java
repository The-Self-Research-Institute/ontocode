package self.research.ontology.auth.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.auth.model.PlanFeatureConfig;
import self.research.ontology.common.db.sql.Instants;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcPlanFeatureConfigRepository implements PlanFeatureConfigRepository {

    private static final String UPSERT = "insert into plan_feature_config (id, plan_id, monthly_price, "
            + "annual_discount_percent, features, limitations, max_members, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?) "
            + "on conflict (plan_id) do update set monthly_price = excluded.monthly_price, "
            + "annual_discount_percent = excluded.annual_discount_percent, features = excluded.features, "
            + "limitations = excluded.limitations, max_members = excluded.max_members, "
            + "updated_at = excluded.updated_at returning id";

    private static final RowMapper<PlanFeatureConfig> ROWS = (rs, i) -> {
        PlanFeatureConfig c = new PlanFeatureConfig();
        c.setId(rs.getString("id"));
        c.setPlanId(rs.getString("plan_id"));
        c.setMonthlyPrice(rs.getInt("monthly_price"));
        c.setAnnualDiscountPercent(rs.getInt("annual_discount_percent"));
        c.setFeatures(AuthSql.readList(rs, "features"));
        c.setLimitations(AuthSql.readList(rs, "limitations"));
        c.setMaxMembers(rs.getInt("max_members"));
        c.setUpdatedAt(Instants.readLocal(rs, "updated_at"));
        return c;
    };

    private final JdbcTemplate jdbc;

    public JdbcPlanFeatureConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PlanFeatureConfig> findByPlanId(String planId) {
        return planId == null ? Optional.empty()
                : AuthSql.first(jdbc.query("select * from plan_feature_config where plan_id = ?", ROWS, planId));
    }

    @Override
    public List<PlanFeatureConfig> findAll() {
        return jdbc.query("select * from plan_feature_config order by plan_id", ROWS);
    }

    @Override
    public PlanFeatureConfig save(PlanFeatureConfig c) {
        String candidateId = AuthSql.idOrNew(c.getId());
        String storedId = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) con -> {
            try (PreparedStatement ps = con.prepareStatement(UPSERT)) {
                AuthSql.bind(con, ps, new Object[]{candidateId, c.getPlanId(), c.getMonthlyPrice(),
                        c.getAnnualDiscountPercent(), AuthSql.textList(c.getFeatures()),
                        AuthSql.textList(c.getLimitations()), c.getMaxMembers(), AuthSql.db(c.getUpdatedAt())});
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
        c.setId(storedId);
        return c;
    }
}
