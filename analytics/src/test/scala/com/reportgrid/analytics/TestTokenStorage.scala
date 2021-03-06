package com.reportgrid.analytics

import blueeyes._
import blueeyes.concurrent.Future
import blueeyes.concurrent.test._
import blueeyes.json.JsonAST._
import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.json.JPathImplicits._
import blueeyes.persistence.mongo._
import blueeyes.util.Clock

import org.joda.time._
import net.lag.configgy.ConfigMap
import com.weiglewilczek.slf4s.Logger

import org.specs2.mutable.Specification
import org.specs2.specification.{Outside, Scope}
import org.scalacheck.Gen._

import scalaz.{Success, Validation}
import scalaz.Scalaz._

trait TestTokenStorage {
  def TestToken: Token

  def populateTestTokens(database: Database, tokensCollection: MongoCollection) = {
    val RootTokenJ: JObject      = Token.Root.serialize.asInstanceOf[JObject]
    val TestTokenJ: JObject      = Token.Test.serialize.asInstanceOf[JObject]

    val rootTokenFuture  = database(upsert(tokensCollection).set(RootTokenJ))
    val testTokenFuture  = database(upsert(tokensCollection).set(TestTokenJ))

    (rootTokenFuture zip testTokenFuture) 
  }

  val tokenCache = new scala.collection.mutable.HashMap[String, Token]
  lazy val tokenManager = new TokenStorage {
    tokenCache.put(Token.Root.tokenId, Token.Root)
    tokenCache.put(TestToken.tokenId, TestToken)

    def lookup(tokenId: String): Future[Option[Token]] = Future.sync(tokenCache.get(tokenId))
    def listChildren(parent: Token): Future[List[Token]] = Future.sync {
      tokenCache flatMap { case (_, v) => v.parentTokenId.exists(_ == parent.tokenId).option(v) } toList 
    }

    def issueNew(parent: Token, path: Path, permissions: Permissions, expires: DateTime, limits: Limits): Future[Validation[String, Token]] = {
      val newToken = parent.issue(path, permissions, expires, limits)
      tokenCache.put(newToken.tokenId, newToken)
      Future.sync(newToken.success[String])
    }

    protected def deleteToken(token: Token): Future[Token] = {
      Future.sync(tokenCache.remove(token.tokenId).getOrElse(token))
    }
  }
}

// vim: set ts=4 sw=4 et:
