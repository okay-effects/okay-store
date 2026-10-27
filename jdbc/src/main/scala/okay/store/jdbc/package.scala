package okay.store

/** the dialects are `core`'s (`okay.store.Dialect`), named here as they were */
package object jdbc {
  type Dialect = okay.store.Dialect
  val Dialect: okay.store.Dialect.type = okay.store.Dialect
}
