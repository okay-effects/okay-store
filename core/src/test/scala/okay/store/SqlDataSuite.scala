package okay.store

import okay.effects.json.Json

/** `Data` as SQL, as text — what any runner of SQL is given (JDBC here and in `jdbc`'s tests against the reference;
 * D1 and Postgres drivers in okay-serverless) */
class SqlDataSuite extends munit.FunSuite {
  val w: Where = Where.And(List(Where.Eq(List("item"), Json.Str("tea")), Where.Not(Where.Greater(List("qty"), Json.num(2)))))

  test("a query on SQLite: the condition two-valued, the arguments SQL's own, in order") {
    val s = SqlData.query(Dialect.sqlite, "sales", w, List(Order(List("qty"), descending = true)), Some(10))
    assertEquals(s.sql, "SELECT key, doc FROM \"sales\" WHERE (COALESCE((json_extract(doc, '$.item') = ?), FALSE)) AND " +
      "(NOT (COALESCE((json_extract(doc, '$.qty') > ?), FALSE))) ORDER BY json_extract(doc, '$.qty') DESC LIMIT 10")
    assertEquals(s.args, List(SqlArg.Text("tea"), SqlArg.Whole(2)))
  }
  test("the same on Postgres: values compared as jsonb, given as JSON text") {
    val s = SqlData.count(Dialect.postgres, "sales", w)
    assert(s.sql.contains("(doc::jsonb #> '{\"item\"}') = ?::jsonb"), s.sql)
    assertEquals(s.args, List(SqlArg.Text("\"tea\""), SqlArg.Text("2")))
  }
  test("a put, the sequences, a document read back") {
    assertEquals(SqlData.put(Dialect.sqlite, "sales", "k1", Json.obj("a" -> Json.num(1))),
      SqlStatement("INSERT OR REPLACE INTO \"sales\" (\"key\", \"doc\") VALUES (?, ?)",
        List(SqlArg.Text("k1"), SqlArg.Text("{\"a\":1}"))))
    assertEquals(SqlData.fresh(Dialect.postgres, "sales").args, List(SqlArg.Text("sales")))
    assertEquals(SqlData.doc("{\"a\":[1,true]}"), Json.obj("a" -> Json.arr(Json.num(1), Json.Bool(true))))
  }
}
