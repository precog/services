package com.reportgrid.billing.scratch

import blueeyes.core.service.HttpClient
import blueeyes.core.service.engines.HttpClientXLightWeb
import blueeyes.core.data.ByteChunk
import blueeyes.core.data.BijectionsChunkString._
import blueeyes.core.data.BijectionsChunkJson._
import blueeyes.concurrent.Future
import blueeyes.core.http.HttpResponse
import blueeyes.json.JsonAST._
import blueeyes.json.JsonParser

import com.reportgrid.billing.RealTokenGenerator
import blueeyes.core.http.MimeTypes._
import com.reportgrid.analytics.Token

import scalaz._
import scalaz.Scalaz._

object TokenCreation {

  val httpClient: HttpClient[ByteChunk] = new HttpClientXLightWeb
  val url = "http://api.reportgrid.com/services/analytics/v1/tokens/"

  def main(args: Array[String]) = {
    val token = Token.Test.tokenId
    val tg = new RealTokenGenerator(httpClient, token, url)

    dumpTokens(token)

    val nt = tg.newToken()

    dumpTokens(token)

    val df: Future[Unit] = nt.flatMap {
      case Success(t) => {
        tg.deleteToken(t)
      }
      case _ => Future.sync(Unit)
    }

    while (!df.isDone) {}

    dumpTokens(token)
  }
  
  def newToken(parent: String): String = {
    val foo: Future[HttpResponse[JValue]] = httpClient.query("tokenId", parent).contentType[ByteChunk](application / json).post[JValue](url)(JsonParser.parse("{ \"path\": \"/test\" }"))
    
    while(!foo.isDone) {
      
    }
    
    val jv = foo.value.get.content.get
    jv.asInstanceOf[JString].value
  }
  
  def removeToken(parent: String, token: String): Unit = {
    val foo: Future[HttpResponse[JValue]] = httpClient.query("tokenId", parent).contentType[ByteChunk](application / json).delete[JValue](url + token)
    while(!foo.isDone) {
      
    }
    Unit
  }

  def numberOfChildren(t: String): Int = {
    children(t).size
  }

  def children(t: String): List[JValue] = {
    try {
      val foo: Future[HttpResponse[JValue]] = httpClient.query("tokenId", t).contentType[ByteChunk](application / json).get[JValue](url)

      while (!foo.isDone) {

      }

      val content = foo.value.get.content.get

      val jarr = content.asInstanceOf[JArray]

      jarr.children
    } catch {
      case ex => Nil
    }
  }
  
  private def dumpTokens(token: String): Unit = {
      
    println(numberOfChildren(token))
    
    children(token).foreach{ c =>
      val cval = c.asInstanceOf[JString].value
      println("  " + cval + ": " + numberOfChildren(cval))
    }
  }
}