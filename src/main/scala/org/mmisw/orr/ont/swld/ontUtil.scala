package org.mmisw.orr.ont.swld

import com.hp.hpl.jena.ontology.{Ontology, OntDocumentManager, OntModelSpec, OntModel}
import com.hp.hpl.jena.rdf.model.{Property, ModelFactory}
import java.io.{FileWriter, File}
import com.typesafe.scalalogging.slf4j.Logging

import com.github.jsonldjava.jena.JenaJSONLD
import org.mmisw.orr.ont.vocabulary.{Omv, OmvMmi}


object ontUtil extends AnyRef with Logging {

  JenaJSONLD.init()

  // todo review
  val mimeMappings: Map[String, String] = Map(
      "rdf"     -> "application/rdf+xml"
    , "owl"     -> "application/rdf+xml"
    , "jsonld"  -> "application/json+ld"    // http://www.ietf.org/rfc/rfc6839.txt
    , "n3"      -> "text/n3"                // http://www.w3.org/TeamSubmission/n3/
    , "ttl"     -> "text/turtle"            // http://www.w3.org/TeamSubmission/turtle/
    , "nt"      -> "text/plain"             // http://www.w3.org/TR/2004/REC-rdf-testcases-20040210/
    , "nq"      -> "application/n-quads"    // http://www.w3.org/TR/2013/NOTE-n-quads-20130409/
    , "trig"    -> "application/trig"       // http://www.w3.org/TR/2013/WD-trig-20130409/
    , "rj"      -> "application/rdf+json"   // https://dvcs.w3.org/hg/rdf/raw-file/default/rdf-json/index.html
  )

  // preliminary
  def convert(uri: String, fromFile: File, fromFormat: String, toFile: File, toFormat: String) : Option[File] = {

    def doIt(fromLang: String, toLang: String): File = {
      if (fromLang == toLang) fromFile
      else {
        // TODO manage resources below
        val model = ModelFactory.createDefaultModel()
        val fromPath = fromFile.toURI.toURL.toString
        model.read(fromPath, fromLang)
        val writer = model.getWriter(toLang)
        logger.info(s"jenaUtil.convert: path=$fromPath")
        val toWriter = new FileWriter(toFile)
        writer.write(model, toWriter, uri)
        toFile
      }
    }

    for {fromLang <- format2lang(storedFormat(fromFormat))
         toLang   <- format2lang(storedFormat(toFormat))
    } yield doIt(fromLang, toLang)
  }

  // for the files actually stored, for example file.rdf serves both the rdf and the owl formats
  def storedFormat(format: String) = format.toLowerCase match {
    case "owl"  | "rdf"     => "rdf"
    case "json" | "jsonld"  => "jsonld"
    case "ttl"  | "n3"      => "n3"
    case f => f
  }

  def getPropsFromOntMetadata(uri: String, file: File, format: String): Map[String,String] = {
    getOntology(uri, file, format) match {
      case Some(ontology) =>
        try extractSomeProps(ontology)
        catch {
          case ex: Throwable =>
            ex.printStackTrace()
            Map[String, String]()
        }

      case _ => Map[String, String]()
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  // private

  /**
   * ad hoc initial mechanism to report some of the ontology metadata.
   */
  private def extractSomeProps(ontology: Ontology): Map[String,String] = {
    var map = Map[String, String]()

    listPropertyValues(ontology, OmvMmi.hasResourceType) match {
      case values if values.size > 0 =>
        val resourceType = values.head
        map = map.updated("resourceType", resourceType)
    }

    listPropertyValues(ontology, Omv.usedOntologyEngineeringTool) match {
      case values if values.size > 0 =>
        val usedOntologyEngineeringTool = values.head
        val ontologyType = if (usedOntologyEngineeringTool == OmvMmi.voc2rdf.getURI)
          "vocabulary"
        else if (usedOntologyEngineeringTool == OmvMmi.vine.getURI)
          "mapping"
        else ""
        if (ontologyType.length > 0)
          map = map.updated("ontologyType", ontologyType)
    }

    map
  }

  private def listPropertyValues(ontology: Ontology, prop: Property): List[String] = {
    val values = collection.mutable.ListBuffer[String]()
    val it = ontology.listPropertyValues(prop)
    if ( it != null) {
      while (it.hasNext) {
        val node = it.next()
        val value = if (node.isLiteral) node.asLiteral().getString else node.asResource().getURI
        values += value
      }
    }
    values.toList
  }

  /**
   * Gets the Jena Ontology object of the given uri from the given file.
   * @param uri
   * @param file
   * @param format
   * @param processImports
   * @return
   */
  private def getOntology(uri: String, file: File, format: String, processImports: Boolean = false):
    Option[Ontology] = {

    logger.debug(s"Loading uri='$uri' file=$file with processImports=$processImports")
    val path = file.getAbsolutePath
    logger.debug(s"path='$path'")
    val source = io.Source.fromFile(path)
    val lang = format2lang(storedFormat(format)).getOrElse(throw new IllegalArgumentException)
    val ontModel = createDefaultOntModel
    ontModel.setDynamicImports(false)
    ontModel.getDocumentManager.setProcessImports(processImports)
    ontModel.read(source.reader(), uri, lang)
    Option(ontModel.getOntology(uri))
  }

  private def createDefaultOntModel: OntModel = {
    val spec: OntModelSpec = new OntModelSpec(OntModelSpec.OWL_MEM)
    spec.setDocumentManager(new OntDocumentManager)
    ModelFactory.createOntologyModel(spec, null)
  }

  // https://jena.apache.org/documentation/io/
  private def format2lang(format: String) = Option(format.toLowerCase match {
    case "rdf"          => "RDF/XML"
    case "jsonld"       => "JSON-LD"
    case "n3"           => "N3"
    case "ttl"          => "Turtle"
    case "nt"           => "N-Triples"
    case "nq"           => "N-Quads"
    case "trig"         => "TriG"
    case "rj"           => "RDF/JSON"
    case _              => null
  })
}
