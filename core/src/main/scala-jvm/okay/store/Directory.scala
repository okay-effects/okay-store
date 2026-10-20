package okay.store

import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters._

/** a directory's files as `Datas.onLines` takes them: `datas.onLines(Directory.read(dir), Directory.append(dir))` */
object Directory {
  def read(dir: String)(name: String): List[String] = {
    val p = Paths.get(dir, name)
    if (Files.exists(p)) Files.readAllLines(p).asScala.toList else Nil
  }
  def append(dir: String)(name: String, line: String): Unit = {
    val p = Paths.get(dir, name); Files.createDirectories(p.getParent)
    Files.write(p, (line + "\n").getBytes("UTF-8"), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE); ()
  }
}
