package controllers;

import play.*;
import play.db.DB;
import play.libs.WS;
import play.libs.WS.HttpResponse;
import play.mvc.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import org.apache.lucene.search.BooleanFilter;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.unit.TimeValue;

import static org.elasticsearch.index.query.QueryBuilders.*;

import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import static org.elasticsearch.index.query.FilterBuilders.*;

import org.elasticsearch.index.query.AndFilterBuilder;
import org.elasticsearch.index.query.GeoBoundingBoxFilterBuilder;
import org.elasticsearch.index.query.GeoDistanceFilterBuilder;
import org.elasticsearch.index.query.OrFilterBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.TextQueryBuilder;
import org.elasticsearch.index.query.TextQueryBuilder.Operator;
import org.elasticsearch.index.search.geo.GeoDistanceFilter;
import org.elasticsearch.node.Node;
import org.elasticsearch.search.SearchHit;
import org.gbif.ecat.model.ParsedName;
import org.gbif.ecat.parser.NameParser;

import com.google.gson.JsonElement;
import com.mongodb.DBRef;
import com.mongodb.util.JSON;

import static org.elasticsearch.node.NodeBuilder.*;


import models.*;

public class Occurrences extends Controller {
    
	public static void search(String taxaSearch, String placeSearch, boolean onlyWithCoordinates, Integer from) {   
		  			  		
	  Search search = Search.parser(taxaSearch, placeSearch, onlyWithCoordinates);
      Float[] boundingBox = null;
	  
      if (!search.place.isEmpty())
      {
        boundingBox = Search.extractBoundingBox(search.place);
      }    	      	
	  int pagesize = 50;
	  if (from == null) from = 0;
	  Settings settings = ImmutableSettings.settingsBuilder()
	  	  .put("cluster.name", "elasticsearch").put("client.transport.sniff", true).build();
	  Client client = new TransportClient(settings).addTransportAddress(new InetSocketTransportAddress("134.157.190.208", 9300));
	  	  	  	  	 	 	  	  	  	  
	  QueryBuilder scientificNameQ = null;
	  QueryBuilder genusQ = null;
	  QueryBuilder genusInterpretedQ = null;
	  QueryBuilder localityQ = null;
	  QueryBuilder countyQ = null;
	  QueryBuilder countryQ = null;
	  QueryBuilder countryCodeQ = null;
	  
	  QueryBuilder boundingBoxLatitudeQ = null, boundingBoxLongitudeQ = null;
	  
	  if (boundingBox != null)
	  {
		
		boundingBoxLatitudeQ = rangeQuery("decimalLatitude_interpreted").from(boundingBox[0]).to(boundingBox[2]);
		//System.out.println("bboxLat " + boundingBoxLatitudeQ.toString());
		
		boundingBoxLongitudeQ = rangeQuery("decimalLongitude_interpreted").from(boundingBox[1]).to(boundingBox[3]);
		//System.out.println("bboxLong " + boundingBoxLongitudeQ.toString());
	  }
	  
	  if (!search.taxa.isEmpty())
	  { 
	    scientificNameQ = textQuery("scientificName", search.taxa);
	    genusInterpretedQ = textQuery("genus_interpreted", search.taxa);
	    genusQ = textQuery("genus", search.taxa);
	  }
	  if (!search.place.isEmpty())
	  { 
		localityQ = textQuery("locality", search.place);
	    countyQ = textQuery("county", search.place);
	    countryQ = termQuery("country", search.place);
	    countryCodeQ = textQuery("countryCode", search.place);
	  }
 	 
	  
	  FilterBuilder f = null;
	  if (search.onlyWithCoordinates)
	  {	
		f = boolFilter().must(existsFilter("decimalLatitude_interpreted")).must(existsFilter("decimalLongitude_interpreted"));  
	  }
	  	  
	  //QueryBuilder q18 = textQuery("specificEpithet_interpreted", search.taxonomy);
	  
	  QueryBuilder q = null;
	  
	  System.out.println("taxa: " + search.taxa + "&& place: " + search.place);
	  
	  
	  if (!search.taxa.isEmpty() && !search.place.isEmpty())
	  {
	    if (boundingBoxLatitudeQ != null && boundingBoxLongitudeQ != null)
	    {
	      q = boolQuery()
	    	.must(boolQuery()	    
	    	  .must(boolQuery()
	    			  .should(scientificNameQ)
	    			  .should(genusInterpretedQ)
	    			  .should(genusQ))
	    	  .must(boolQuery()
	    			  .should(localityQ)
	    			  .should(countyQ)
	    			  .should(countryQ)
	    			  .should(countryCodeQ)
	    			  .should(boolQuery().must(boundingBoxLatitudeQ).must(boundingBoxLongitudeQ)).boost(2))			 
	    	);  	
	    }
	    else
		{
		  q = boolQuery()	    
			.must(boolQuery()
					.should(localityQ)
					.should(countyQ)
					.should(countryQ)
					.should(countryCodeQ)); 	
		} 
	  }
	  else if (!search.taxa.isEmpty() && search.place.isEmpty())
	  {
		q = boolQuery() 
			.must(boolQuery()
	    			  .should(scientificNameQ)
	    			  .should(genusInterpretedQ)
	    			  .should(genusQ));  
	  }
	  else if (search.taxa.isEmpty() && !search.place.isEmpty())
	  {
		if (boundingBoxLatitudeQ != null && boundingBoxLongitudeQ != null)
		{
		  q = boolQuery()
		  	.must(boolQuery()	    
		   			  .should(localityQ)
		   			  .should(countyQ)
		   			  .should(countryQ)
		   			  .should(countryCodeQ)
		   			  .should(boolQuery().must(boundingBoxLatitudeQ).must(boundingBoxLongitudeQ)).boost(2));  	
		}
		else
		{
		  q = boolQuery()	    
			.must(boolQuery()
					.should(localityQ)
					.should(countyQ)
					.should(countryQ)
					.should(countryCodeQ)); 	
		}
		 
	  }
	  
	  
	  
	  SearchResponse response;
	  if (search.onlyWithCoordinates == true)
	  {
		response = client.prepareSearch("idx_occurrence").setFrom(from).setSize(50).setSearchType(SearchType.DFS_QUERY_THEN_FETCH).setQuery(q).setFilter(f).setExplain(true).execute().actionGet();
	  }
	  else
	  {
	    response = client.prepareSearch("idx_occurrence").setFrom(from).setSize(50).setSearchType(SearchType.DFS_QUERY_THEN_FETCH).setQuery(q).setExplain(true).execute().actionGet();
	  }
      List<Occurrence> occurrences = new ArrayList<Occurrence>();
      Long nbHits = response.getHits().getTotalHits();
      for (SearchHit hit : response.getHits()) {   
          Occurrence occurrence = new Occurrence();	
    	  occurrence.id = (Integer) hit.getSource().get("_id");
          occurrence.scientificName = (String) hit.getSource().get("scientificName");
          occurrence.catalogNumber = (String) hit.getSource().get("catalogNumber");
          occurrence.specificEpithet_interpreted = (String) hit.getSource().get("specificEpithet_interpreted");
          occurrence.decimalLatitude = (String) hit.getSource().get("decimalLatitude");
          occurrence.decimalLongitude = (String) hit.getSource().get("decimalLongitude");
          DBRef dbRef = (DBRef) JSON.parse((String) hit.getSource().get("dataset"));
          String dataset_id = (String) dbRef.getId();
          Dataset dataset = Dataset.findById(dataset_id);
          occurrence.dataset = dataset;
          occurrence.score = hit.getScore();
          occurrences.add(occurrence);
          if (occurrences.size() >= 50) break;        
      }  
      int current = from/pagesize;
      from += 50;
      client.close();
      
      int occurrencesTotalPages;
  	  if (nbHits < pagesize) 
      {
        pagesize = nbHits.intValue();
        occurrencesTotalPages = 1;
      }
      else occurrencesTotalPages = (int) (nbHits/pagesize);	
      
      render("Application/Search/occurrences.html", occurrences, search, nbHits, from, occurrencesTotalPages, pagesize, current);
    }
	    
    public static void show(Integer id) {
      Settings settings = ImmutableSettings.settingsBuilder()
      		  .put("cluster.name", "elasticsearch") .put("client.transport.sniff", true).build();
      Client client = new TransportClient(settings).addTransportAddress(new InetSocketTransportAddress("134.157.190.208", 9300));
      QueryBuilder q = termQuery("_id", id);
      SearchResponse response = client.prepareSearch("idx_occurrence").setSearchType(SearchType.DEFAULT).setQuery(q).setExplain(true).execute().actionGet();
      Occurrence occurrence = new Occurrence();
      occurrence.typee = (String) response.getHits().getAt(0).getSource().get("typee");
      occurrence.modified = (String) response.getHits().getAt(0).getSource().get("modified");
      occurrence.language = (String) response.getHits().getAt(0).getSource().get("language");
      occurrence.rights = (String) response.getHits().getAt(0).getSource().get("rights");
      occurrence.rightsHolder = (String) response.getHits().getAt(0).getSource().get("rightsHolder");
      occurrence.accessRights = (String) response.getHits().getAt(0).getSource().get("accessRights");
      occurrence.bibliographicCitation = (String) response.getHits().getAt(0).getSource().get("bibliographicCitation");
      occurrence.referencess = (String) response.getHits().getAt(0).getSource().get("referencess");
      occurrence.institutionID = (String) response.getHits().getAt(0).getSource().get("institutionID");
      occurrence.collectionID = (String) response.getHits().getAt(0).getSource().get("collectionID");
      occurrence.datasetID = (String) response.getHits().getAt(0).getSource().get("datasetID");
      occurrence.institutionCode = (String) response.getHits().getAt(0).getSource().get("institutionCode");
      occurrence.collectionCode = (String) response.getHits().getAt(0).getSource().get("collectionCode");
      occurrence.datasetName = (String) response.getHits().getAt(0).getSource().get("datasetName");
      occurrence.ownerInstitutionCode = (String) response.getHits().getAt(0).getSource().get("ownerInstitutionCode");
      occurrence.basisOfRecord = (String) response.getHits().getAt(0).getSource().get("basisOfRecord");
      occurrence.informationWithheld = (String) response.getHits().getAt(0).getSource().get("informationWithheld");
      occurrence.dataGeneralizations = (String) response.getHits().getAt(0).getSource().get("dataGeneralizations");
      occurrence.dynamicProperties = (String) response.getHits().getAt(0).getSource().get("dynamicProperties");
      occurrence.occurrenceID = (String) response.getHits().getAt(0).getSource().get("occurrenceID");
      occurrence.catalogNumber = (String) response.getHits().getAt(0).getSource().get("catalogNumber");
      occurrence.occurrenceRemarks = (String) response.getHits().getAt(0).getSource().get("occurrenceRemarks");
      occurrence.recordNumber = (String) response.getHits().getAt(0).getSource().get("recordNumber");
      occurrence.recordedBy = (String) response.getHits().getAt(0).getSource().get("recordedBy");
      occurrence.individualID = (String) response.getHits().getAt(0).getSource().get("individualID");
      occurrence.individualCount = (String) response.getHits().getAt(0).getSource().get("individualCount");
      occurrence.sex = (String) response.getHits().getAt(0).getSource().get("sex");
      occurrence.lifeStage = (String) response.getHits().getAt(0).getSource().get("lifeStage");
      occurrence.reproductiveCondition = (String) response.getHits().getAt(0).getSource().get("reproductiveCondition");
      occurrence.behavior = (String) response.getHits().getAt(0).getSource().get("behavior");
      occurrence.establishmentMeans = (String) response.getHits().getAt(0).getSource().get("establishmentMeans");
      occurrence.occurrenceStatus = (String) response.getHits().getAt(0).getSource().get("occurrenceStatus");
      occurrence.preparations = (String) response.getHits().getAt(0).getSource().get("preparations");
      occurrence.disposition = (String) response.getHits().getAt(0).getSource().get("disposition");
      occurrence.otherCatalogNumbers = (String) response.getHits().getAt(0).getSource().get("otherCatalogNumbers");
      occurrence.previousIdentifications = (String) response.getHits().getAt(0).getSource().get("previousIdentifications");
      occurrence.associatedMedia = (String) response.getHits().getAt(0).getSource().get("associatedMedia");
      occurrence.associatedReferences = (String) response.getHits().getAt(0).getSource().get("associatedReferences");
      occurrence.associatedOccurrences = (String) response.getHits().getAt(0).getSource().get("associatedOccurrences");
      occurrence.associatedSequences = (String) response.getHits().getAt(0).getSource().get("associatedSequences");
      occurrence.associatedTaxa = (String) response.getHits().getAt(0).getSource().get("associatedTaxa");
      occurrence.eventID = (String) response.getHits().getAt(0).getSource().get("eventID");
      occurrence.samplingProtocol = (String) response.getHits().getAt(0).getSource().get("samplingProtocol");
      occurrence.samplingEffort = (String) response.getHits().getAt(0).getSource().get("samplingEffort");
      occurrence.eventDate = (String) response.getHits().getAt(0).getSource().get("eventDate");
      occurrence.eventTime = (String) response.getHits().getAt(0).getSource().get("eventTime");
      occurrence.startDayOfYear = (String) response.getHits().getAt(0).getSource().get("startDayOfYear");
      occurrence.endDayofYear = (String) response.getHits().getAt(0).getSource().get("endDayofYear");
      occurrence.year = (String) response.getHits().getAt(0).getSource().get("year");
      occurrence.month = (String) response.getHits().getAt(0).getSource().get("month");
      occurrence.day = (String) response.getHits().getAt(0).getSource().get("day");
      occurrence.verbatimEventDate = (String) response.getHits().getAt(0).getSource().get("verbatimEventDate");
      occurrence.habitat = (String) response.getHits().getAt(0).getSource().get("habitat");
      occurrence.fieldNumber = (String) response.getHits().getAt(0).getSource().get("fieldNumber");
      occurrence.fieldNotes = (String) response.getHits().getAt(0).getSource().get("fieldNotes");
      occurrence.eventRemarks = (String) response.getHits().getAt(0).getSource().get("eventRemarks");
      occurrence.locationID = (String) response.getHits().getAt(0).getSource().get("locationID");
      occurrence.higherGeographyID = (String) response.getHits().getAt(0).getSource().get("higherGeographyID");
      occurrence.higherGeography = (String) response.getHits().getAt(0).getSource().get("higherGeography");
      occurrence.continent = (String) response.getHits().getAt(0).getSource().get("continent");
      occurrence.waterBody = (String) response.getHits().getAt(0).getSource().get("waterBody");
      occurrence.islandGroup = (String) response.getHits().getAt(0).getSource().get("islandGroup");
      occurrence.island = (String) response.getHits().getAt(0).getSource().get("island");
      occurrence.country = (String) response.getHits().getAt(0).getSource().get("country");
      occurrence.countryCode = (String) response.getHits().getAt(0).getSource().get("countryCode");
      occurrence.stateProvince = (String) response.getHits().getAt(0).getSource().get("stateProvince");
      occurrence.county = (String) response.getHits().getAt(0).getSource().get("county");
      occurrence.municipality = (String) response.getHits().getAt(0).getSource().get("municipality");
      occurrence.locality = (String) response.getHits().getAt(0).getSource().get("locality");
      occurrence.verbatimLocality = (String) response.getHits().getAt(0).getSource().get("verbatimLocality");
      occurrence.verbatimElevation = (String) response.getHits().getAt(0).getSource().get("verbatimElevation");
      occurrence.minimumElevationInMeters = (String) response.getHits().getAt(0).getSource().get("minimumElevationInMeters");
      occurrence.maximumElevationInMeters = (String) response.getHits().getAt(0).getSource().get("maximumElevationInMeters");
      occurrence.verbatimDepth = (String) response.getHits().getAt(0).getSource().get("verbatimDepth");
      occurrence.minimumDepthInMeters = (String) response.getHits().getAt(0).getSource().get("minimumDepthInMeters");
      occurrence.maximumDepthInMeters = (String) response.getHits().getAt(0).getSource().get("maximumDepthInMeters");
      occurrence.minimumDistanceAboveSurfaceInMeters = (String) response.getHits().getAt(0).getSource().get("minimumDistanceAboveSurfaceInMeters");
      occurrence.maximumDistanceAboveSurfaceInMeters = (String) response.getHits().getAt(0).getSource().get("maximumDistanceAboveSurfaceInMeters");
      occurrence.locationAccordingTo = (String) response.getHits().getAt(0).getSource().get("locationAccordingTo");
      occurrence.locationRemarks = (String) response.getHits().getAt(0).getSource().get("locationRemarks");
      occurrence.verbatimCoordinates = (String) response.getHits().getAt(0).getSource().get("verbatimCoordinates");
      occurrence.verbatimLatitude = (String) response.getHits().getAt(0).getSource().get("verbatimLatitude");
      occurrence.verbatimLongitude = (String) response.getHits().getAt(0).getSource().get("verbatimLongitude");
      occurrence.verbatimCoordinateSystem = (String) response.getHits().getAt(0).getSource().get("verbatimCoordinateSystem");
      occurrence.verbatimSRS = (String) response.getHits().getAt(0).getSource().get("verbatimSRS");
      occurrence.decimalLatitude = (String) response.getHits().getAt(0).getSource().get("decimalLatitude");
      occurrence.decimalLongitude = (String) response.getHits().getAt(0).getSource().get("decimalLongitude");
      occurrence.geodeticDatum = (String) response.getHits().getAt(0).getSource().get("geodeticDatum");
      occurrence.coordinateUncertaintyInMeters = (String) response.getHits().getAt(0).getSource().get("coordinateUncertaintyInMeters");
      occurrence.coordinatePrecision = (String) response.getHits().getAt(0).getSource().get("coordinatePrecision");
      occurrence.pointRadiusSpatialFit = (String) response.getHits().getAt(0).getSource().get("pointRadiusSpatialFit");
      occurrence.footprintWKT = (String) response.getHits().getAt(0).getSource().get("footprintWKT");
      occurrence.footprintSRS = (String) response.getHits().getAt(0).getSource().get("footprintSRS");
      occurrence.footprintSpatialFit = (String) response.getHits().getAt(0).getSource().get("footprintSpatialFit");
      occurrence.georeferencedBy = (String) response.getHits().getAt(0).getSource().get("georeferencedBy");
      occurrence.georeferencedDate = (String) response.getHits().getAt(0).getSource().get("georeferencedDate");
      occurrence.georeferenceProtocol = (String) response.getHits().getAt(0).getSource().get("georeferenceProtocol");
      occurrence.georeferenceSources = (String) response.getHits().getAt(0).getSource().get("georeferenceSources");
      occurrence.georeferenceVerificationStatus = (String) response.getHits().getAt(0).getSource().get("georeferenceVerificationStatus");
      occurrence.georeferenceRemarks = (String) response.getHits().getAt(0).getSource().get("georeferenceRemarks");
      occurrence.geologicalContextID = (String) response.getHits().getAt(0).getSource().get("geologicalContextID");
      occurrence.earliestEonOrLowestEonothem = (String) response.getHits().getAt(0).getSource().get("earliestEonOrLowestEonothem");
      occurrence.latestEonOrHighestEonothem = (String) response.getHits().getAt(0).getSource().get("latestEonOrHighestEonothem");
      occurrence.earliestEraOrLowestErathem = (String) response.getHits().getAt(0).getSource().get("earliestEraOrLowestErathem");
      occurrence.latestEraOrHighestErathem = (String) response.getHits().getAt(0).getSource().get("latestEraOrHighestErathem");
      occurrence.earliestPeriodOrLowestSystem = (String) response.getHits().getAt(0).getSource().get("earliestPeriodOrLowestSystem");
      occurrence.latestPeriodOrHighestSystem = (String) response.getHits().getAt(0).getSource().get("latestPeriodOrHighestSystem");
      occurrence.earliestEpochOrLowestSeries = (String) response.getHits().getAt(0).getSource().get("earliestEpochOrLowestSeries");
      occurrence.latestEpochOrHighestSeries = (String) response.getHits().getAt(0).getSource().get("latestEpochOrHighestSeries");
      occurrence.earliestAgeOrLowestStage = (String) response.getHits().getAt(0).getSource().get("earliestAgeOrLowestStage");
      occurrence.latestAgeOrHighestStage = (String) response.getHits().getAt(0).getSource().get("latestAgeOrHighestStage");
      occurrence.lowestBiostratigraphicZone = (String) response.getHits().getAt(0).getSource().get("lowestBiostratigraphicZone");
      occurrence.highestBiostratigraphicZone = (String) response.getHits().getAt(0).getSource().get("highestBiostratigraphicZone");
      occurrence.lithostratigraphicTerms = (String) response.getHits().getAt(0).getSource().get("lithostratigraphicTerms");
      occurrence.groupp = (String) response.getHits().getAt(0).getSource().get("groupp");
      occurrence.formation = (String) response.getHits().getAt(0).getSource().get("formation");
      occurrence.member = (String) response.getHits().getAt(0).getSource().get("member");
      occurrence.bed = (String) response.getHits().getAt(0).getSource().get("bed");
      occurrence.identificationID = (String) response.getHits().getAt(0).getSource().get("identificationID");
      occurrence.identifiedBy = (String) response.getHits().getAt(0).getSource().get("identifiedBy");
      occurrence.dateIdentified = (String) response.getHits().getAt(0).getSource().get("dateIdentified");
      occurrence.identificationVerificationStatus = (String) response.getHits().getAt(0).getSource().get("identificationVerificationStatus");
      occurrence.identificationRemarks = (String) response.getHits().getAt(0).getSource().get("identificationRemarks");
      occurrence.identificationQualifier = (String) response.getHits().getAt(0).getSource().get("identificationQualifier");
      occurrence.typeStatus = (String) response.getHits().getAt(0).getSource().get("typeStatus");
      occurrence.taxonID = (String) response.getHits().getAt(0).getSource().get("taxonID");
      occurrence.scientificNameID = (String) response.getHits().getAt(0).getSource().get("scientificNameID");
      occurrence.acceptedNameUsageID = (String) response.getHits().getAt(0).getSource().get("acceptedNameUsageID");
      occurrence.parentNameUsageID = (String) response.getHits().getAt(0).getSource().get("parentNameUsageID");
      occurrence.originalNameUsageID = (String) response.getHits().getAt(0).getSource().get("originalNameUsageID");
      occurrence.nameAccordingToID = (String) response.getHits().getAt(0).getSource().get("nameAccordingToID");
      occurrence.namePublishedInID = (String) response.getHits().getAt(0).getSource().get("namePublishedInID");
      occurrence.taxonConceptID = (String) response.getHits().getAt(0).getSource().get("taxonConceptID");
      occurrence.scientificName = (String) response.getHits().getAt(0).getSource().get("scientificName");
      occurrence.acceptedNameUsage = (String) response.getHits().getAt(0).getSource().get("acceptedNameUsage");
      occurrence.parentNameUsage = (String) response.getHits().getAt(0).getSource().get("parentNameUsage");
      occurrence.originalNameUsage = (String) response.getHits().getAt(0).getSource().get("originalNameUsage");
      occurrence.nameAccordingTo = (String) response.getHits().getAt(0).getSource().get("nameAccordingTo");
      occurrence.namePublishedIn = (String) response.getHits().getAt(0).getSource().get("namePublishedIn");
      occurrence.namePublishedInYear = (String) response.getHits().getAt(0).getSource().get("namePublishedInYear");
      occurrence.higherClassification = (String) response.getHits().getAt(0).getSource().get("higherClassification");
      occurrence.kingdom = (String) response.getHits().getAt(0).getSource().get("kingdom");
      occurrence.phylum = (String) response.getHits().getAt(0).getSource().get("phylum");
      occurrence.classs = (String) response.getHits().getAt(0).getSource().get("classs");
      occurrence.orderr = (String) response.getHits().getAt(0).getSource().get("orderr");
      occurrence.family = (String) response.getHits().getAt(0).getSource().get("family");
      occurrence.genus = (String) response.getHits().getAt(0).getSource().get("genus");
      occurrence.subgenus = (String) response.getHits().getAt(0).getSource().get("subgenus");
      occurrence.specificEpithet = (String) response.getHits().getAt(0).getSource().get("specificEpithet");
      occurrence.infraSpecificEpithet = (String) response.getHits().getAt(0).getSource().get("infraSpecificEpithet");
      occurrence.kingdom_interpreted = (String) response.getHits().getAt(0).getSource().get("kingdom_interpreted");
      occurrence.phylum_interpreted = (String) response.getHits().getAt(0).getSource().get("phylum_interpreted");
      occurrence.classs_interpreted = (String) response.getHits().getAt(0).getSource().get("classs_interpreted");
      occurrence.orderr_interpreted = (String) response.getHits().getAt(0).getSource().get("orderr_interpreted");
      occurrence.family_interpreted = (String) response.getHits().getAt(0).getSource().get("family_interpreted");
      occurrence.genus_interpreted = (String) response.getHits().getAt(0).getSource().get("genus_interpreted");
      occurrence.subgenus_interpreted = (String) response.getHits().getAt(0).getSource().get("subgenus_interpreted");
      occurrence.specificEpithet_interpreted = (String) response.getHits().getAt(0).getSource().get("specificEpithet_interpreted");
      occurrence.infraSpecificEpithet_interpreted = (String) response.getHits().getAt(0).getSource().get("infraSpecificEpithet_interpreted");
      occurrence.taxonRank = (String) response.getHits().getAt(0).getSource().get("taxonRank");
      occurrence.verbatimTaxonRank = (String) response.getHits().getAt(0).getSource().get("verbatimTaxonRank");
      occurrence.scientificNameAuthorship = (String) response.getHits().getAt(0).getSource().get("scientificNameAuthorship");
      occurrence.vernacularName = (String) response.getHits().getAt(0).getSource().get("vernacularName");
      occurrence.nomenclaturalCode = (String) response.getHits().getAt(0).getSource().get("nomenclaturalCode");
      occurrence.taxonomicStatus = (String) response.getHits().getAt(0).getSource().get("taxonomicStatus");
      occurrence.nomenclaturalStatus = (String) response.getHits().getAt(0).getSource().get("nomenclaturalStatus");
      occurrence.taxonRemarks = (String) response.getHits().getAt(0).getSource().get("taxonRemarks");
      occurrence.taxonStatus = (String) response.getHits().getAt(0).getSource().get("taxonStatus");
      
      DBRef dbRef = (DBRef) JSON.parse((String) response.getHits().getAt(0).getSource().get("dataset"));
		String dataset_id = (String) dbRef.getId();
		Dataset dataset = Dataset.findById(dataset_id);
		occurrence.dataset = dataset;

      client.close();
      
      Taxa taxa = Taxas.getTaxonomy(occurrence);
      
      render(occurrence, taxa);
    } 
}