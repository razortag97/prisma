package com.prisma.deploy.connector.mongo.impl

import com.prisma.deploy.connector.MigrationPersistence
import com.prisma.shared.models.MigrationStatus.MigrationStatus
import com.prisma.shared.models.{Migration, MigrationId, MigrationStatus}
import com.prisma.utils.mongo.MongoExtensions
import org.joda.time.DateTime
import org.mongodb.scala.bson.BsonDateTime
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.Sorts._
import org.mongodb.scala.model.{Filters, Updates}
import org.mongodb.scala.{MongoCollection, MongoDatabase}

import scala.concurrent.{ExecutionContext, Future}

case class MigrationPersistenceImpl(
    internalDatabase: MongoDatabase
)(implicit ec: ExecutionContext)
    extends MigrationPersistence
    with MongoExtensions {

  val migrations: MongoCollection[Document] = internalDatabase.getCollection("Migration")

  def lock(): Future[Unit] = Future.successful(())

  override def byId(migrationId: MigrationId): Future[Option[Migration]] = {
    migrations
      .find(Filters.and(projectIdFilter(migrationId.projectId), revisionFilter(migrationId.revision)))
      .collect
      .toFuture()
      .map(_.headOption.map(DbMapper.convertToMigrationModel))
  }

  override def loadAll(projectId: String): Future[Seq[Migration]] = {
    migrations
      .find(projectIdFilter(projectId))
      .sort(descending("revision"))
      .collect
      .toFuture()
      .map(_.map(DbMapper.convertToMigrationModel))
  }

  override def create(migration: Migration): Future[Migration] = {
    def lastRevision =
      migrations
        .find(projectIdFilter(migration.projectId))
        .sort(descending("revision"))
        .collect
        .toFuture()
        .map(_.headOption.map(DbMapper.convertToMigrationModel(_).revision))

    for {
      lastRevision       <- lastRevision
      withRevisionBumped = migration.copy(revision = lastRevision.getOrElse(0) + 1)
      _                  <- migrations.insertOne(DbMapper.convertToDocument(withRevisionBumped)).toFuture()
    } yield migration.copy(revision = withRevisionBumped.revision)
  }

  private def updateColumn(id: MigrationId, field: String, value: Any) = {
    migrations
      .updateOne(Filters.and(projectIdFilter(id.projectId), revisionFilter(id.revision)), Updates.set(field, value))
      .toFuture
      .map(_ => ())
  }

  override def updateMigrationStatus(id: MigrationId, status: MigrationStatus): Future[Unit] = {
    updateColumn(id, "status", status.toString)
  }

  override def updateMigrationErrors(id: MigrationId, errors: Vector[String]): Future[Unit] = {
    updateColumn(id, "errors", errors)
  }

  override def updateMigrationApplied(id: MigrationId, applied: Int): Future[Unit] = {
    updateColumn(id, "applied", applied)
  }

  override def updateMigrationRolledBack(id: MigrationId, rolledBack: Int): Future[Unit] = {
    updateColumn(id, "rolledBack", rolledBack)
  }

  override def updateStartedAt(id: MigrationId, startedAt: DateTime): Future[Unit] = {
    updateColumn(id, "startedAt", BsonDateTime(startedAt.getMillis))
  }

  override def updateFinishedAt(id: MigrationId, finishedAt: DateTime): Future[Unit] = {
    updateColumn(id, "finishedAt", BsonDateTime(finishedAt.getMillis))

  }

  override def getLastMigration(projectId: String): Future[Option[Migration]] = {
    migrations
      .find(Filters.and(projectIdFilter(projectId), statusFilter(MigrationStatus.Success)))
      .sort(descending("revision"))
      .collect
      .toFuture()
      .map(_.headOption.map(DbMapper.convertToMigrationModel))
  }

  override def getNextMigration(projectId: String): Future[Option[Migration]] = {
    migrations
      .find(
        Filters.and(
          projectIdFilter(projectId),
          Filters.or(statusFilter(MigrationStatus.Pending), statusFilter(MigrationStatus.InProgress), statusFilter(MigrationStatus.RollingBack))
        )
      )
      .sort(ascending("revision"))
      .collect
      .toFuture()
      .map(_.headOption.map(DbMapper.convertToMigrationModel))
  }

  override def loadDistinctUnmigratedProjectIds(): Future[Seq[String]] = {
    migrations
      .find(Filters.or(statusFilter(MigrationStatus.Pending), statusFilter(MigrationStatus.InProgress), statusFilter(MigrationStatus.RollingBack)))
      .collect
      .toFuture()
      .map(_.map(DbMapper.convertToMigrationModel(_).projectId).distinct)
  }

  private def projectIdFilter(projectId: String)    = Filters.eq("projectId", projectId)
  private def revisionFilter(revision: Int)         = Filters.eq("revision", revision)
  private def statusFilter(status: MigrationStatus) = Filters.eq("status", status.toString)
}
