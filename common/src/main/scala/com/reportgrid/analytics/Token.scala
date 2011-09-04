package com.reportgrid.analytics

import org.joda.time.{DateTime, DateTimeZone}

/** A token gives a user access to a path in the ReportGrid virtual file
 * system. Every customer of ReportGrid has a "root" token which gives
 * them access to a customer-specific path in the file system. They can
 * use this token to issue other tokens with access to subfolders of
 * their own root folder.
 *
 * Tokens have permissions (read/write/share), expiration dates, and limits.
 */
case class Token(tokenId: String, parentTokenId: Option[String], accountTokenId: String, path: Path, permissions: Permissions, expires: DateTime, limits: Limits) {
  def expired  = expires.getMillis <= new DateTime(DateTimeZone.UTC).getMillis
  def canRead  = permissions.read
  def canWrite = permissions.write
  def canShare = permissions.share

  def isAccountToken = tokenId == accountTokenId

  def selectorName = if (isAccountToken) "accountTokenId" else "parentTokenId"

  /** Issues a new token derived from this one. By default, the token will
   * have the same permissions, expiration date, and limit of this token.
   */
  def issue(relativePath: Path = "/", permissions: Permissions = this.permissions, expires: DateTime = this.expires, limits: Limits = this.limits): Token = Token(
    tokenId        = Token.newUUID(),
    parentTokenId  = Some(this.tokenId),
    accountTokenId = this.accountTokenId,
    path           = this.path / relativePath,
    permissions    = permissions.limitTo(this.permissions),
    expires        = new DateTime(expires.getMillis.min(this.expires.getMillis), DateTimeZone.UTC),
    limits         = limits.limitTo(this.limits)
  )

  def relativeTo(owner: Token) = copy(path = this.path - owner.path)

  def absoluteFrom(owner: Token) = copy(path = owner.path + this.path)
}

object Token {
  private def newUUID() = java.util.UUID.randomUUID().toString.toUpperCase

  val Never = new DateTime(java.lang.Long.MAX_VALUE, DateTimeZone.UTC)

  lazy val Root = Token("8E680858-329C-4F31-BEE3-2AD15FB67EED", None, "8E680858-329C-4F31-BEE3-2AD15FB67EED", "/", Permissions(true, true, true, true), Never, Limits.None)

  lazy val Test = Token(
    tokenId        = "A3BC1539-E8A9-4207-BB41-3036EC2C6E6D",
    parentTokenId  = Some(Root.tokenId),
    accountTokenId = "A3BC1539-E8A9-4207-BB41-3036EC2C6E6D",
    path           = "test-account-root",
    permissions    = Permissions(true, true, true, true),
    expires        = Never,
    limits         = Limits(order = 2, depth = 3, limit = 20, tags = 2)
  )

  lazy val Benchmark = Token(
    tokenId        = "C7A18C95-3619-415B-A89B-4CE47693E4CC",
    parentTokenId  = Some(Root.tokenId),
    accountTokenId = "C7A18C95-3619-415B-A89B-4CE47693E4CC",
    path           = "test-account-benchmark",
    permissions    = Permissions(true, true, true, true),
    expires        = Never,
    limits         = Limits(order = 3, depth = 5, limit = 20, tags = 2)
  )

  def newAccount(path: Path, limits: Limits, permissions: Permissions = Permissions.All, expires: DateTime = Never): Token = {
    val newTokenId = newUUID()

    new Token(
      tokenId        = newTokenId,
      parentTokenId  = Some(Root.tokenId),
      accountTokenId = newTokenId,
      path           = path,
      permissions    = Permissions(true, true, true, true),
      expires        = Never,
      limits         = limits.limitTo(Token.Root.limits)
    )
  }
}
