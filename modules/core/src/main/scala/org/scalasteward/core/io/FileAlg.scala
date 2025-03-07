/*
 * Copyright 2018-2019 scala-steward contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.scalasteward.core.io

import better.files.File
import cats.effect.Sync
import cats.implicits._
import cats.{Functor, Traverse}
import fs2.Stream
import org.apache.commons.io.FileUtils
import org.scalasteward.core.util
import org.scalasteward.core.util.MonadThrowable

trait FileAlg[F[_]] {
  def createTemporarily[A](file: File, content: String)(fa: F[A]): F[A]

  def deleteForce(file: File): F[Unit]

  def ensureExists(dir: File): F[File]

  def home: F[File]

  def isSymlink(file: File): F[Boolean]

  def removeTemporarily[A](file: File)(fa: F[A]): F[A]

  def readFile(file: File): F[Option[String]]

  def walk(dir: File): Stream[F, File]

  def writeFile(file: File, content: String): F[Unit]

  def containsString(file: File, string: String)(implicit F: Functor[F]): F[Boolean] =
    readFile(file).map(_.fold(false)(_.contains(string)))

  def editFile(file: File, edit: String => Option[String])(
      implicit F: MonadThrowable[F]
  ): F[Boolean] =
    readFile(file)
      .flatMap(_.flatMap(edit).fold(F.pure(false))(writeFile(file, _).as(true)))
      .adaptError { case t => new Throwable(s"failed to edit $file", t) }

  def editFiles[G[_]](files: G[File], edit: String => Option[String])(
      implicit
      F: MonadThrowable[F],
      G: Traverse[G]
  ): F[Boolean] =
    files.traverse(editFile(_, edit)).map(_.foldLeft(false)(_ || _))

  def findSourceFilesContaining(dir: File, string: String)(implicit F: Sync[F]): F[List[File]] =
    walk(dir)
      .filter(isSourceFile)
      .through(util.evalFilter(isNoSymlink))
      .through(util.evalFilter(containsString(_, string)))
      .compile
      .toList

  def isNoSymlink(file: File)(implicit F: Functor[F]): F[Boolean] =
    isSymlink(file).map(!_)

  def writeFileData(dir: File, fileData: FileData): F[Unit] =
    writeFile(dir / fileData.name, fileData.content)
}

object FileAlg {
  def create[F[_]](implicit F: Sync[F]): FileAlg[F] =
    new FileAlg[F] {
      override def createTemporarily[A](file: File, content: String)(fa: F[A]): F[A] =
        F.bracket(writeFile(file, content))(_ => fa)(_ => deleteForce(file))

      override def deleteForce(file: File): F[Unit] =
        F.delay(if (file.exists) FileUtils.forceDelete(file.toJava))

      def ensureExists(dir: File): F[File] =
        F.delay {
          if (!dir.exists) dir.createDirectories()
          dir
        }

      override def home: F[File] =
        F.delay(File.home)

      override def isSymlink(file: File): F[Boolean] =
        F.delay(file.isSymbolicLink)

      override def removeTemporarily[A](file: File)(fa: F[A]): F[A] =
        F.bracket {
          F.delay {
            val copyOptions = File.CopyOptions(overwrite = true)
            if (file.exists) Some(file.moveTo(File.newTemporaryFile())(copyOptions)) else None
          }
        } { _ =>
          fa
        } {
          case Some(tmpFile) => F.delay(tmpFile.moveTo(file)).void
          case None          => F.unit
        }

      override def readFile(file: File): F[Option[String]] =
        F.delay(if (file.exists) Some(file.contentAsString) else None)

      override def walk(dir: File): Stream[F, File] =
        Stream.eval(F.delay(dir.walk())).flatMap(Stream.fromIterator(_))

      override def writeFile(file: File, content: String): F[Unit] =
        file.parentOption.fold(F.unit)(ensureExists(_).void) >> F.delay(file.write(content)).void
    }
}
