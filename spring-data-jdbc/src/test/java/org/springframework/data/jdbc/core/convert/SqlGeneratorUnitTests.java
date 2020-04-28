/*
 * Copyright 2017-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.jdbc.core.convert;

import static java.util.Collections.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.data.relational.core.sql.SqlIdentifier.*;

import java.util.Map;
import java.util.Set;

import org.assertj.core.api.SoftAssertions;
import org.junit.Before;
import org.junit.Test;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jdbc.core.PropertyPathTestingUtils;
import org.springframework.data.jdbc.core.mapping.AggregateReference;
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.jdbc.core.mapping.PersistentPropertyPathTestUtils;
import org.springframework.data.jdbc.testing.AnsiDialect;
import org.springframework.data.mapping.PersistentPropertyPath;
import org.springframework.data.relational.core.dialect.Dialect;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.NamingStrategy;
import org.springframework.data.relational.core.mapping.PersistentPropertyPathExtension;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;
import org.springframework.data.relational.core.sql.Aliased;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.data.relational.core.sql.Table;

/**
 * Unit tests for the {@link SqlGenerator}.
 *
 * @author Jens Schauder
 * @author Greg Turnquist
 * @author Oleksandr Kucher
 * @author Bastian Wilhelm
 * @author Mark Paluch
 * @author Tom Hombergs
 * @author Milan Milanov
 */
public class SqlGeneratorUnitTests {

	static final Identifier BACKREF = Identifier.of(unquoted("backref"), "some-value", String.class);

	SqlGenerator sqlGenerator;
	NamingStrategy namingStrategy = new PrefixingNamingStrategy();
	RelationalMappingContext context = new JdbcMappingContext(namingStrategy);
	JdbcConverter converter = new BasicJdbcConverter(context, (identifier, path) -> {
		throw new UnsupportedOperationException();
	});

	@Before
	public void setUp() {
		this.sqlGenerator = createSqlGenerator(DummyEntity.class);
	}

	SqlGenerator createSqlGenerator(Class<?> type) {

		return createSqlGenerator(type, NonQuotingDialect.INSTANCE);
	}

	SqlGenerator createSqlGenerator(Class<?> type, Dialect dialect) {

		RelationalPersistentEntity<?> persistentEntity = context.getRequiredPersistentEntity(type);

		return new SqlGenerator(context, converter, persistentEntity, dialect);
	}

	@Test // DATAJDBC-112
	public void findOne() {

		String sql = sqlGenerator.getFindOne();

		SoftAssertions softAssertions = new SoftAssertions();
		softAssertions.assertThat(sql) //
				.startsWith("SELECT") //
				.contains("dummy_entity.id1 AS id1,") //
				.contains("dummy_entity.x_name AS x_name,") //
				.contains("dummy_entity.x_other AS x_other,") //
				.contains("ref.x_l1id AS ref_x_l1id") //
				.contains("ref.x_content AS ref_x_content").contains(" FROM dummy_entity") //
				.contains("ON ref.dummy_entity = dummy_entity.id1") //
				.contains("WHERE dummy_entity.id1 = :id") //
				// 1-N relationships do not get loaded via join
				.doesNotContain("Element AS elements");
		softAssertions.assertAll();
	}

	@Test // DATAJDBC-112
	public void cascadingDeleteFirstLevel() {

		String sql = sqlGenerator.createDeleteByPath(getPath("ref", DummyEntity.class));

		assertThat(sql).isEqualTo("DELETE FROM referenced_entity WHERE referenced_entity.dummy_entity = :rootId");
	}

	@Test // DATAJDBC-112
	public void cascadingDeleteByPathSecondLevel() {

		String sql = sqlGenerator.createDeleteByPath(getPath("ref.further", DummyEntity.class));

		assertThat(sql).isEqualTo(
				"DELETE FROM second_level_referenced_entity WHERE second_level_referenced_entity.referenced_entity IN (SELECT referenced_entity.x_l1id FROM referenced_entity WHERE referenced_entity.dummy_entity = :rootId)");
	}

	@Test // DATAJDBC-112
	public void deleteAll() {

		String sql = sqlGenerator.createDeleteAllSql(null);

		assertThat(sql).isEqualTo("DELETE FROM dummy_entity");
	}

	@Test // DATAJDBC-112
	public void cascadingDeleteAllFirstLevel() {

		String sql = sqlGenerator.createDeleteAllSql(getPath("ref", DummyEntity.class));

		assertThat(sql).isEqualTo("DELETE FROM referenced_entity WHERE referenced_entity.dummy_entity IS NOT NULL");
	}

	@Test // DATAJDBC-112
	public void cascadingDeleteAllSecondLevel() {

		String sql = sqlGenerator.createDeleteAllSql(getPath("ref.further", DummyEntity.class));

		assertThat(sql).isEqualTo(
				"DELETE FROM second_level_referenced_entity WHERE second_level_referenced_entity.referenced_entity IN (SELECT referenced_entity.x_l1id FROM referenced_entity WHERE referenced_entity.dummy_entity IS NOT NULL)");
	}

	@Test // DATAJDBC-227
	public void deleteAllMap() {

		String sql = sqlGenerator.createDeleteAllSql(getPath("mappedElements", DummyEntity.class));

		assertThat(sql).isEqualTo("DELETE FROM element WHERE element.dummy_entity IS NOT NULL");
	}

	@Test // DATAJDBC-227
	public void deleteMapByPath() {

		String sql = sqlGenerator.createDeleteByPath(getPath("mappedElements", DummyEntity.class));

		assertThat(sql).isEqualTo("DELETE FROM element WHERE element.dummy_entity = :rootId");
	}

	@Test // DATAJDBC-101
	public void findAllSortedByUnsorted() {

		String sql = sqlGenerator.getFindAll(Sort.unsorted());

		assertThat(sql).doesNotContain("ORDER BY");
	}

	@Test // DATAJDBC-101
	public void findAllSortedBySingleField() {

		String sql = sqlGenerator.getFindAll(Sort.by("x_name"));

		assertThat(sql).contains("SELECT", //
				"dummy_entity.id1 AS id1", //
				"dummy_entity.x_name AS x_name", //
				"dummy_entity.x_other AS x_other", //
				"ref.x_l1id AS ref_x_l1id", //
				"ref.x_content AS ref_x_content", //
				"ref_further.x_l2id AS ref_further_x_l2id", //
				"ref_further.x_something AS ref_further_x_something", //
				"FROM dummy_entity ", //
				"LEFT OUTER JOIN referenced_entity AS ref ON ref.dummy_entity = dummy_entity.id1", //
				"LEFT OUTER JOIN second_level_referenced_entity AS ref_further ON ref_further.referenced_entity = ref.x_l1id", //
				"ORDER BY x_name ASC");
	}

	@Test // DATAJDBC-101
	public void findAllSortedByMultipleFields() {

		String sql = sqlGenerator.getFindAll(
				Sort.by(new Sort.Order(Sort.Direction.DESC, "x_name"), new Sort.Order(Sort.Direction.ASC, "x_other")));

		assertThat(sql).contains("SELECT", //
				"dummy_entity.id1 AS id1", //
				"dummy_entity.x_name AS x_name", //
				"dummy_entity.x_other AS x_other", //
				"ref.x_l1id AS ref_x_l1id", //
				"ref.x_content AS ref_x_content", //
				"ref_further.x_l2id AS ref_further_x_l2id", //
				"ref_further.x_something AS ref_further_x_something", //
				"FROM dummy_entity ", //
				"LEFT OUTER JOIN referenced_entity AS ref ON ref.dummy_entity = dummy_entity.id1", //
				"LEFT OUTER JOIN second_level_referenced_entity AS ref_further ON ref_further.referenced_entity = ref.x_l1id", //
				"ORDER BY x_name DESC", //
				"x_other ASC");
	}

	@Test // DATAJDBC-101
	public void findAllPagedByUnpaged() {

		String sql = sqlGenerator.getFindAll(Pageable.unpaged());

		assertThat(sql).doesNotContain("ORDER BY").doesNotContain("FETCH FIRST").doesNotContain("OFFSET");
	}

	@Test // DATAJDBC-101
	public void findAllPaged() {

		String sql = sqlGenerator.getFindAll(PageRequest.of(2, 20));

		assertThat(sql).contains("SELECT", //
				"dummy_entity.id1 AS id1", //
				"dummy_entity.x_name AS x_name", //
				"dummy_entity.x_other AS x_other", //
				"ref.x_l1id AS ref_x_l1id", //
				"ref.x_content AS ref_x_content", //
				"ref_further.x_l2id AS ref_further_x_l2id", //
				"ref_further.x_something AS ref_further_x_something", //
				"FROM dummy_entity ", //
				"LEFT OUTER JOIN referenced_entity AS ref ON ref.dummy_entity = dummy_entity.id1", //
				"LEFT OUTER JOIN second_level_referenced_entity AS ref_further ON ref_further.referenced_entity = ref.x_l1id", //
				"OFFSET 40", //
				"LIMIT 20");
	}

	@Test // DATAJDBC-101
	public void findAllPagedAndSorted() {

		String sql = sqlGenerator.getFindAll(PageRequest.of(3, 10, Sort.by("x_name")));

		assertThat(sql).contains("SELECT", //
				"dummy_entity.id1 AS id1", //
				"dummy_entity.x_name AS x_name", //
				"dummy_entity.x_other AS x_other", //
				"ref.x_l1id AS ref_x_l1id", //
				"ref.x_content AS ref_x_content", //
				"ref_further.x_l2id AS ref_further_x_l2id", //
				"ref_further.x_something AS ref_further_x_something", //
				"FROM dummy_entity ", //
				"LEFT OUTER JOIN referenced_entity AS ref ON ref.dummy_entity = dummy_entity.id1", //
				"LEFT OUTER JOIN second_level_referenced_entity AS ref_further ON ref_further.referenced_entity = ref.x_l1id", //
				"ORDER BY x_name ASC", //
				"OFFSET 30", //
				"LIMIT 10");
	}

	@Test // DATAJDBC-131, DATAJDBC-111
	public void findAllByProperty() {

		// this would get called when ListParent is the element type of a Set
		String sql = sqlGenerator.getFindAllByProperty(BACKREF, null, false);

		assertThat(sql).contains("SELECT", //
				"dummy_entity.id1 AS id1", //
				"dummy_entity.x_name AS x_name", //
				"dummy_entity.x_other AS x_other", //
				"ref.x_l1id AS ref_x_l1id", //
				"ref.x_content AS ref_x_content", //
				"ref_further.x_l2id AS ref_further_x_l2id", //
				"ref_further.x_something AS ref_further_x_something", //
				"FROM dummy_entity ", //
				"LEFT OUTER JOIN referenced_entity AS ref ON ref.dummy_entity = dummy_entity.id1", //
				"LEFT OUTER JOIN second_level_referenced_entity AS ref_further ON ref_further.referenced_entity = ref.x_l1id", //
				"WHERE dummy_entity.backref = :backref");
	}

	@Test // DATAJDBC-223
	public void findAllByPropertyWithMultipartIdentifier() {

		// this would get called when ListParent is the element type of a Set
		Identifier parentIdentifier = Identifier.of(unquoted("backref"), "some-value", String.class) //
				.withPart(unquoted("backref_key"), "key-value", Object.class);
		String sql = sqlGenerator.getFindAllByProperty(parentIdentifier, null, false);

		assertThat(sql).contains("SELECT", //
				"dummy_entity.id1 AS id1", //
				"dummy_entity.x_name AS x_name", //
				"dummy_entity.x_other AS x_other", //
				"ref.x_l1id AS ref_x_l1id", //
				"ref.x_content AS ref_x_content", //
				"ref_further.x_l2id AS ref_further_x_l2id", //
				"ref_further.x_something AS ref_further_x_something", //
				"FROM dummy_entity ", //
				"LEFT OUTER JOIN referenced_entity AS ref ON ref.dummy_entity = dummy_entity.id1", //
				"LEFT OUTER JOIN second_level_referenced_entity AS ref_further ON ref_further.referenced_entity = ref.x_l1id", //
				"dummy_entity.backref = :backref", //
				"dummy_entity.backref_key = :backref_key");
	}

	@Test // DATAJDBC-131, DATAJDBC-111
	public void findAllByPropertyWithKey() {

		// this would get called when ListParent is th element type of a Map
		String sql = sqlGenerator.getFindAllByProperty(BACKREF, unquoted("key-column"), false);

		assertThat(sql).isEqualTo("SELECT dummy_entity.id1 AS id1, dummy_entity.x_name AS x_name, " //
				+ "dummy_entity.x_other AS x_other, " //
				+ "ref.x_l1id AS ref_x_l1id, ref.x_content AS ref_x_content, "
				+ "ref_further.x_l2id AS ref_further_x_l2id, ref_further.x_something AS ref_further_x_something, " //
				+ "dummy_entity.key-column AS key-column " //
				+ "FROM dummy_entity " //
				+ "LEFT OUTER JOIN referenced_entity AS ref ON ref.dummy_entity = dummy_entity.id1 " //
				+ "LEFT OUTER JOIN second_level_referenced_entity AS ref_further ON ref_further.referenced_entity = ref.x_l1id " //
				+ "WHERE dummy_entity.backref = :backref");
	}

	@Test(expected = IllegalArgumentException.class) // DATAJDBC-130
	public void findAllByPropertyOrderedWithoutKey() {
		sqlGenerator.getFindAllByProperty(BACKREF, null, true);
	}

	@Test // DATAJDBC-131, DATAJDBC-111
	public void findAllByPropertyWithKeyOrdered() {

		// this would get called when ListParent is th element type of a Map
		String sql = sqlGenerator.getFindAllByProperty(BACKREF, unquoted("key-column"), true);

		assertThat(sql).isEqualTo("SELECT dummy_entity.id1 AS id1, dummy_entity.x_name AS x_name, " //
				+ "dummy_entity.x_other AS x_other, " //
				+ "ref.x_l1id AS ref_x_l1id, ref.x_content AS ref_x_content, "
				+ "ref_further.x_l2id AS ref_further_x_l2id, ref_further.x_something AS ref_further_x_something, " //
				+ "dummy_entity.key-column AS key-column " //
				+ "FROM dummy_entity " //
				+ "LEFT OUTER JOIN referenced_entity AS ref ON ref.dummy_entity = dummy_entity.id1 " //
				+ "LEFT OUTER JOIN second_level_referenced_entity AS ref_further ON ref_further.referenced_entity = ref.x_l1id " //
				+ "WHERE dummy_entity.backref = :backref " + "ORDER BY key-column");
	}

	@Test // DATAJDBC-219
	public void updateWithVersion() {

		SqlGenerator sqlGenerator = createSqlGenerator(VersionedEntity.class, AnsiDialect.INSTANCE);

		assertThat(sqlGenerator.getUpdateWithVersion()).containsSubsequence( //
				"UPDATE", //
				"\"VERSIONED_ENTITY\"", //
				"SET", //
				"WHERE", //
				"\"id1\" = :id1", //
				"AND", //
				"\"X_VERSION\" = :___oldOptimisticLockingVersion");
	}

	@Test // DATAJDBC-264
	public void getInsertForEmptyColumnList() {

		SqlGenerator sqlGenerator = createSqlGenerator(IdOnlyEntity.class);

		String insert = sqlGenerator.getInsert(emptySet());

		assertThat(insert).endsWith("()");
	}

	@Test // DATAJDBC-334
	public void getInsertForQuotedColumnName() {

		SqlGenerator sqlGenerator = createSqlGenerator(EntityWithQuotedColumnName.class, AnsiDialect.INSTANCE);

		String insert = sqlGenerator.getInsert(emptySet());

		assertThat(insert).isEqualTo("INSERT INTO \"ENTITY_WITH_QUOTED_COLUMN_NAME\" " //
				+ "(\"test\"\"_@123\") " + "VALUES (:test_123)");
	}

	@Test // DATAJDBC-266
	public void joinForOneToOneWithoutIdIncludesTheBackReferenceOfTheOuterJoin() {

		SqlGenerator sqlGenerator = createSqlGenerator(ParentOfNoIdChild.class, AnsiDialect.INSTANCE);

		String findAll = sqlGenerator.getFindAll();

		assertThat(findAll).containsSubsequence("SELECT",
				"\"child\".\"PARENT_OF_NO_ID_CHILD\" AS \"CHILD_PARENT_OF_NO_ID_CHILD\"", "FROM");
	}

	@Test // DATAJDBC-262
	public void update() {

		SqlGenerator sqlGenerator = createSqlGenerator(DummyEntity.class, AnsiDialect.INSTANCE);

		assertThat(sqlGenerator.getUpdate()).containsSubsequence( //
				"UPDATE", //
				"\"DUMMY_ENTITY\"", //
				"SET", //
				"WHERE", //
				"\"id1\" = :id1");
	}

	@Test // DATAJDBC-324
	public void readOnlyPropertyExcludedFromQuery_when_generateUpdateSql() {

		final SqlGenerator sqlGenerator = createSqlGenerator(EntityWithReadOnlyProperty.class, AnsiDialect.INSTANCE);

		assertThat(sqlGenerator.getUpdate()).isEqualToIgnoringCase( //
				"UPDATE \"ENTITY_WITH_READ_ONLY_PROPERTY\" " //
						+ "SET \"X_NAME\" = :X_NAME " //
						+ "WHERE \"ENTITY_WITH_READ_ONLY_PROPERTY\".\"X_ID\" = :X_ID" //
		);
	}

	@Test // DATAJDBC-334
	public void getUpdateForQuotedColumnName() {

		SqlGenerator sqlGenerator = createSqlGenerator(EntityWithQuotedColumnName.class, AnsiDialect.INSTANCE);

		String update = sqlGenerator.getUpdate();

		assertThat(update).isEqualTo("UPDATE \"ENTITY_WITH_QUOTED_COLUMN_NAME\" " //
				+ "SET \"test\"\"_@123\" = :test_123 " //
				+ "WHERE \"ENTITY_WITH_QUOTED_COLUMN_NAME\".\"test\"\"_@id\" = :test_id");
	}

	@Test // DATAJDBC-324
	public void readOnlyPropertyExcludedFromQuery_when_generateInsertSql() {

		final SqlGenerator sqlGenerator = createSqlGenerator(EntityWithReadOnlyProperty.class, AnsiDialect.INSTANCE);

		assertThat(sqlGenerator.getInsert(emptySet())).isEqualToIgnoringCase( //
				"INSERT INTO \"ENTITY_WITH_READ_ONLY_PROPERTY\" (\"X_NAME\") " //
						+ "VALUES (:x_name)" //
		);
	}

	@Test // DATAJDBC-324
	public void readOnlyPropertyIncludedIntoQuery_when_generateFindAllSql() {

		final SqlGenerator sqlGenerator = createSqlGenerator(EntityWithReadOnlyProperty.class);

		assertThat(sqlGenerator.getFindAll()).isEqualToIgnoringCase("SELECT "
				+ "entity_with_read_only_property.x_id AS x_id, " + "entity_with_read_only_property.x_name AS x_name, "
				+ "entity_with_read_only_property.x_read_only_value AS x_read_only_value "
				+ "FROM entity_with_read_only_property");
	}

	@Test // DATAJDBC-324
	public void readOnlyPropertyIncludedIntoQuery_when_generateFindAllByPropertySql() {

		final SqlGenerator sqlGenerator = createSqlGenerator(EntityWithReadOnlyProperty.class);

		assertThat(sqlGenerator.getFindAllByProperty(BACKREF, unquoted("key-column"), true)).isEqualToIgnoringCase( //
				"SELECT " //
						+ "entity_with_read_only_property.x_id AS x_id, " //
						+ "entity_with_read_only_property.x_name AS x_name, " //
						+ "entity_with_read_only_property.x_read_only_value AS x_read_only_value, " //
						+ "entity_with_read_only_property.key-column AS key-column " //
						+ "FROM entity_with_read_only_property " //
						+ "WHERE entity_with_read_only_property.backref = :backref " //
						+ "ORDER BY key-column" //
		);
	}

	@Test // DATAJDBC-324
	public void readOnlyPropertyIncludedIntoQuery_when_generateFindAllInListSql() {

		final SqlGenerator sqlGenerator = createSqlGenerator(EntityWithReadOnlyProperty.class);

		assertThat(sqlGenerator.getFindAllInList()).isEqualToIgnoringCase( //
				"SELECT " //
						+ "entity_with_read_only_property.x_id AS x_id, " //
						+ "entity_with_read_only_property.x_name AS x_name, " //
						+ "entity_with_read_only_property.x_read_only_value AS x_read_only_value " //
						+ "FROM entity_with_read_only_property " //
						+ "WHERE entity_with_read_only_property.x_id IN (:ids)" //
		);
	}

	@Test // DATAJDBC-324
	public void readOnlyPropertyIncludedIntoQuery_when_generateFindOneSql() {

		final SqlGenerator sqlGenerator = createSqlGenerator(EntityWithReadOnlyProperty.class);

		assertThat(sqlGenerator.getFindOne()).isEqualToIgnoringCase( //
				"SELECT " //
						+ "entity_with_read_only_property.x_id AS x_id, " //
						+ "entity_with_read_only_property.x_name AS x_name, " //
						+ "entity_with_read_only_property.x_read_only_value AS x_read_only_value " //
						+ "FROM entity_with_read_only_property " //
						+ "WHERE entity_with_read_only_property.x_id = :id" //
		);
	}

	@Test // DATAJDBC-340
	public void deletingLongChain() {

		assertThat(
				createSqlGenerator(Chain4.class).createDeleteByPath(getPath("chain3.chain2.chain1.chain0", Chain4.class))) //
						.isEqualTo("DELETE FROM chain0 " + //
								"WHERE chain0.chain1 IN (" + //
								"SELECT chain1.x_one " + //
								"FROM chain1 " + //
								"WHERE chain1.chain2 IN (" + //
								"SELECT chain2.x_two " + //
								"FROM chain2 " + //
								"WHERE chain2.chain3 IN (" + //
								"SELECT chain3.x_three " + //
								"FROM chain3 " + //
								"WHERE chain3.chain4 = :rootId" + //
								")))");
	}

	@Test // DATAJDBC-359
	public void deletingLongChainNoId() {

		assertThat(createSqlGenerator(NoIdChain4.class)
				.createDeleteByPath(getPath("chain3.chain2.chain1.chain0", NoIdChain4.class))) //
						.isEqualTo("DELETE FROM no_id_chain0 WHERE no_id_chain0.no_id_chain4 = :rootId");
	}

	@Test // DATAJDBC-359
	public void deletingLongChainNoIdWithBackreferenceNotReferencingTheRoot() {

		assertThat(createSqlGenerator(IdIdNoIdChain.class)
				.createDeleteByPath(getPath("idNoIdChain.chain4.chain3.chain2.chain1.chain0", IdIdNoIdChain.class))) //
						.isEqualTo( //
								"DELETE FROM no_id_chain0 " //
										+ "WHERE no_id_chain0.no_id_chain4 IN (" //
										+ "SELECT no_id_chain4.x_four " //
										+ "FROM no_id_chain4 " //
										+ "WHERE no_id_chain4.id_no_id_chain IN (" //
										+ "SELECT id_no_id_chain.x_id " //
										+ "FROM id_no_id_chain " //
										+ "WHERE id_no_id_chain.id_id_no_id_chain = :rootId" //
										+ "))");
	}

	@Test // DATAJDBC-340
	public void noJoinForSimpleColumn() {
		assertThat(generateJoin("id", DummyEntity.class)).isNull();
	}

	@Test // DATAJDBC-340
	public void joinForSimpleReference() {

		SqlGenerator.Join join = generateJoin("ref", DummyEntity.class);

		SoftAssertions.assertSoftly(softly -> {

			softly.assertThat(join.getJoinTable().getName()).isEqualTo(SqlIdentifier.quoted("REFERENCED_ENTITY"));
			softly.assertThat(join.getJoinColumn().getTable()).isEqualTo(join.getJoinTable());
			softly.assertThat(join.getJoinColumn().getName()).isEqualTo(SqlIdentifier.quoted("DUMMY_ENTITY"));
			softly.assertThat(join.getParentId().getName()).isEqualTo(SqlIdentifier.quoted("id1"));
			softly.assertThat(join.getParentId().getTable().getName()).isEqualTo(SqlIdentifier.quoted("DUMMY_ENTITY"));
		});
	}

	@Test // DATAJDBC-340
	public void noJoinForCollectionReference() {

		SqlGenerator.Join join = generateJoin("elements", DummyEntity.class);

		assertThat(join).isNull();

	}

	@Test // DATAJDBC-340
	public void noJoinForMappedReference() {

		SqlGenerator.Join join = generateJoin("mappedElements", DummyEntity.class);

		assertThat(join).isNull();
	}

	@Test // DATAJDBC-340
	public void joinForSecondLevelReference() {

		SqlGenerator.Join join = generateJoin("ref.further", DummyEntity.class);

		SoftAssertions.assertSoftly(softly -> {

			softly.assertThat(join.getJoinTable().getName())
					.isEqualTo(SqlIdentifier.quoted("SECOND_LEVEL_REFERENCED_ENTITY"));
			softly.assertThat(join.getJoinColumn().getTable()).isEqualTo(join.getJoinTable());
			softly.assertThat(join.getJoinColumn().getName()).isEqualTo(SqlIdentifier.quoted("REFERENCED_ENTITY"));
			softly.assertThat(join.getParentId().getName()).isEqualTo(SqlIdentifier.quoted("X_L1ID"));
			softly.assertThat(join.getParentId().getTable().getName()).isEqualTo(SqlIdentifier.quoted("REFERENCED_ENTITY"));
		});
	}

	@Test // DATAJDBC-340
	public void joinForOneToOneWithoutId() {

		SqlGenerator.Join join = generateJoin("child", ParentOfNoIdChild.class);
		Table joinTable = join.getJoinTable();

		SoftAssertions.assertSoftly(softly -> {

			softly.assertThat(joinTable.getName()).isEqualTo(SqlIdentifier.quoted("NO_ID_CHILD"));
			softly.assertThat(joinTable).isInstanceOf(Aliased.class);
			softly.assertThat(((Aliased) joinTable).getAlias()).isEqualTo(SqlIdentifier.quoted("child"));
			softly.assertThat(join.getJoinColumn().getTable()).isEqualTo(joinTable);
			softly.assertThat(join.getJoinColumn().getName()).isEqualTo(SqlIdentifier.quoted("PARENT_OF_NO_ID_CHILD"));
			softly.assertThat(join.getParentId().getName()).isEqualTo(SqlIdentifier.quoted("X_ID"));
			softly.assertThat(join.getParentId().getTable().getName())
					.isEqualTo(SqlIdentifier.quoted("PARENT_OF_NO_ID_CHILD"));

		});
	}

	private SqlGenerator.Join generateJoin(String path, Class<?> type) {
		return createSqlGenerator(type, AnsiDialect.INSTANCE)
				.getJoin(new PersistentPropertyPathExtension(context, PropertyPathTestingUtils.toPath(path, type, context)));
	}

	@Test // DATAJDBC-340
	public void simpleColumn() {

		assertThat(generatedColumn("id", DummyEntity.class)) //
				.extracting(c -> c.getName(), c -> c.getTable().getName(), c -> getAlias(c.getTable()), this::getAlias)
				.containsExactly(SqlIdentifier.quoted("id1"), SqlIdentifier.quoted("DUMMY_ENTITY"), null,
						SqlIdentifier.quoted("id1"));
	}

	@Test // DATAJDBC-340
	public void columnForIndirectProperty() {

		assertThat(generatedColumn("ref.l1id", DummyEntity.class)) //
				.extracting(c -> c.getName(), c -> c.getTable().getName(), c -> getAlias(c.getTable()), this::getAlias) //
				.containsExactly(SqlIdentifier.quoted("X_L1ID"), SqlIdentifier.quoted("REFERENCED_ENTITY"),
						SqlIdentifier.quoted("ref"), SqlIdentifier.quoted("REF_X_L1ID"));
	}

	@Test // DATAJDBC-340
	public void noColumnForReferencedEntity() {

		assertThat(generatedColumn("ref", DummyEntity.class)).isNull();
	}

	@Test // DATAJDBC-340
	public void columnForReferencedEntityWithoutId() {

		assertThat(generatedColumn("child", ParentOfNoIdChild.class)) //
				.extracting(c -> c.getName(), c -> c.getTable().getName(), c -> getAlias(c.getTable()), this::getAlias) //
				.containsExactly(SqlIdentifier.quoted("PARENT_OF_NO_ID_CHILD"), SqlIdentifier.quoted("NO_ID_CHILD"),
						SqlIdentifier.quoted("child"), SqlIdentifier.quoted("CHILD_PARENT_OF_NO_ID_CHILD"));
	}

	private SqlIdentifier getAlias(Object maybeAliased) {

		if (maybeAliased instanceof Aliased) {
			return ((Aliased) maybeAliased).getAlias();
		}
		return null;
	}

	private org.springframework.data.relational.core.sql.Column generatedColumn(String path, Class<?> type) {

		return createSqlGenerator(type, AnsiDialect.INSTANCE)
				.getColumn(new PersistentPropertyPathExtension(context, PropertyPathTestingUtils.toPath(path, type, context)));
	}

	private PersistentPropertyPath<RelationalPersistentProperty> getPath(String path, Class<?> baseType) {
		return PersistentPropertyPathTestUtils.getPath(context, path, baseType);
	}

	@SuppressWarnings("unused")
	static class DummyEntity {

		@Column("id1") @Id Long id;
		String name;
		ReferencedEntity ref;
		Set<Element> elements;
		Map<Integer, Element> mappedElements;
		AggregateReference<OtherAggregate, Long> other;
	}

	static class VersionedEntity extends DummyEntity {
		@Version Integer version;
	}

	@SuppressWarnings("unused")
	static class ReferencedEntity {

		@Id Long l1id;
		String content;
		SecondLevelReferencedEntity further;
	}

	@SuppressWarnings("unused")
	static class SecondLevelReferencedEntity {

		@Id Long l2id;
		String something;
	}

	static class Element {
		@Id Long id;
		String content;
	}

	@SuppressWarnings("unused")
	static class ParentOfNoIdChild {

		@Id Long id;
		NoIdChild child;
	}

	static class NoIdChild {}

	static class OtherAggregate {
		@Id Long id;
		String name;
	}

	private static class PrefixingNamingStrategy implements NamingStrategy {

		@Override
		public String getColumnName(RelationalPersistentProperty property) {
			return "x_" + NamingStrategy.super.getColumnName(property);
		}

	}

	@SuppressWarnings("unused")
	static class IdOnlyEntity {

		@Id Long id;
	}

	@SuppressWarnings("unused")
	static class EntityWithReadOnlyProperty {

		@Id Long id;
		String name;
		@ReadOnlyProperty String readOnlyValue;
	}

	static class EntityWithQuotedColumnName {

		// these column names behave like single double quote in the name since the get quoted and then doubling the double
		// quote escapes it.
		@Id @Column("test\"\"_@id") Long id;
		@Column("test\"\"_@123") String name;
	}

	@SuppressWarnings("unused")
	static class Chain0 {
		@Id Long zero;
		String zeroValue;
	}

	@SuppressWarnings("unused")
	static class Chain1 {
		@Id Long one;
		String oneValue;
		Chain0 chain0;
	}

	@SuppressWarnings("unused")
	static class Chain2 {
		@Id Long two;
		String twoValue;
		Chain1 chain1;
	}

	@SuppressWarnings("unused")
	static class Chain3 {
		@Id Long three;
		String threeValue;
		Chain2 chain2;
	}

	@SuppressWarnings("unused")
	static class Chain4 {
		@Id Long four;
		String fourValue;
		Chain3 chain3;
	}

	static class NoIdChain0 {
		String zeroValue;
	}

	static class NoIdChain1 {
		String oneValue;
		NoIdChain0 chain0;
	}

	static class NoIdChain2 {
		String twoValue;
		NoIdChain1 chain1;
	}

	static class NoIdChain3 {
		String threeValue;
		NoIdChain2 chain2;
	}

	static class NoIdChain4 {
		@Id Long four;
		String fourValue;
		NoIdChain3 chain3;
	}

	static class IdNoIdChain {
		@Id Long id;
		NoIdChain4 chain4;
	}

	static class IdIdNoIdChain {
		@Id Long id;
		IdNoIdChain idNoIdChain;
	}
}
