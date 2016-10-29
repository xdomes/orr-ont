package org.mmisw.orr.ont.app

import java.io.File

import com.novus.salat._
import com.novus.salat.global._
import com.typesafe.scalalogging.{StrictLogging => Logging}
import org.mmisw.orr.ont.{OntologySummaryResult, Setup}
import org.mmisw.orr.ont.db.{Ontology, OntologyVersion}
import org.mmisw.orr.ont.service.{CannotQueryTerm, NoSuchTermFormat, _}

import scala.util.{Failure, Success, Try}

/**
  */
abstract class BaseOntController(implicit setup: Setup,
                                 ontService: OntService,
                                 tsService: TripleStoreService
                                ) extends BaseController
with Logging {

  protected def resolveOntologyVersion(uri: String, versionOpt: Option[String] = None): (Ontology, OntologyVersion, String) = {
    Try(ontService.resolveOntologyVersion(uri, versionOpt)) match {
      case Success(res) => res
      case Failure(exc: NoSuch) => error(404, exc.details)
      case Failure(exc)         => error500(exc)
    }
  }

  protected def resolveOntologyVersion(ont: Ontology, versionOpt: Option[String]): (OntologyVersion, String) = {
    Try(ontService.resolveOntologyVersion(ont, versionOpt)) match {
      case Success(res)         => res
      case Failure(exc: NoSuch) => error(404, exc.details)
      case Failure(exc)         => error500(exc)
    }
  }

  protected def getOntologyFile(uri: String, version: String, reqFormat: String): (File, String) = {
    Try(ontService.getOntologyFile(uri, version, reqFormat)) match {
      case Success(res) => res
      case Failure(exc: NoSuchOntFormat)    => error(406, exc.details)
      case Failure(exc: CannotCreateFormat) => error(406, exc.details)
      case Failure(exc)                     => error500(exc)
    }
  }

  /**
    * Some parameters for purposes of the self-resolution mechanism,
    * in particular for the HTTP/S scheme handling.
    *
    * @param uri              URI to be resolved
    * @param reqFormatOpt     if already captured
    * @param selfResolution   true to try the HTTP-HTTPS scheme change
    */
  protected def resolveOntOrTermUri(uri: String,
                                    reqFormatOpt: Option[String] = None,
                                    selfResolution: Boolean = false
                                   ) = {
    ontService.resolveOntology(uri) match {
      case Some(ont) => completeOntologyUriResolution(ont, reqFormatOpt)

      case None =>
        if (selfResolution) {
          replaceHttpScheme(uri) map { uri2 =>
            ontService.resolveOntology(uri2) match {
              case Some(ont) => completeOntologyUriResolution(ont, reqFormatOpt)

              case None =>
                resolveTermUri(uri, reqFormatOpt)
            }
          }
        }
        else resolveTermUri(uri, reqFormatOpt)
    }
  }

  protected def resolveOntUri(uri: String) = {
    ontService.resolveOntology(uri) match {
      case Some(ont) => completeOntologyUriResolution(ont)
      case None      => error(404, s"'$uri': No such ontology")
    }
  }

  protected def completeOntologyUriResolution(ont: Ontology, reqFormatOpt: Option[String] = None) = {
    val versionOpt: Option[String] = params.get("version")
    val (ontVersion, version) = resolveOntologyVersion(ont, versionOpt)

    // format is the one given if any, or the one in the db:
    val reqFormat = reqFormatOpt.getOrElse(params.get("format").getOrElse(ontVersion.format))

    // format=!md is our mechanism to request for metadata

    if (reqFormat == "!md") {
      // include 'versions' even when a particular version is requested
      val versionsOpt = Some(ont.sortedVersionKeys)
      val ores = ontService.getOntologySummaryResult(ont, ontVersion, version,
        privileged = checkIsAdminOrExtra,
        includeMetadata = true,
        versionsOpt
      )
      grater[OntologySummaryResult].toCompactJSON(ores)
    }
    else {
      val (file, actualFormat) = getOntologyFile(ont.uri, version, reqFormat)
      contentType = formats(actualFormat)
      file
    }
  }

  protected def resolveTermUri(uri: String, reqFormatOpt: Option[String] = None): String = {
    val formatOpt = reqFormatOpt orElse params.get("format")
    tsService.resolveTermUri(uri, formatOpt, acceptHeader) match {
      case Right(TermResponse(result, resultContentType)) =>
        contentType = resultContentType
        result

      case Left(exc) =>
        exc match {
          case NoSuchTermFormat(_, format) => error(406, s"invalid format=$format")
          case CannotQueryTerm(_, msg)     => error(400, s"error querying uri=$uri: $msg")
          case _                           => error500(exc)
        }

      case null => error500(s"Unexpected: got null but Either expected -- Scala compiler bug?")
        // Noted with "self-hosted" test in SequenceSpec
        // The tsService.resolveTermUri above doesn't seem to be called, or at least not
        // properly called because it returns null right away; I even put an immediate
        // 'throw exception' there and it is not triggered at all!
    }
  }

  /**
    * If uri starts with "http:" or "https:", returns a Some
    * with the same uri but with the scheme replaced for the other.
    * Otherwise, None.
    */
  // #31 "https == http for purposes of IRI identification"
  private def replaceHttpScheme(uri: String): Option[String] = {
    if      (uri.startsWith("http:"))  Some("https:" + uri.substring("http:".length))
    else if (uri.startsWith("https:")) Some("http:" +  uri.substring("https:".length))
    else None
  }
}
