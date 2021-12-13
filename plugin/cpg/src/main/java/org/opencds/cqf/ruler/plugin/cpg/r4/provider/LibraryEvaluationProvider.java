package org.opencds.cqf.ruler.plugin.cpg.r4.provider;

import ca.uhn.fhir.cql.common.provider.LibraryResolutionProvider;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.partition.SystemRequestDetails;
import ca.uhn.fhir.jpa.rp.r4.LibraryResourceProvider;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.param.UriParam;
import org.cqframework.cql.cql2elm.CqlTranslatorOptions;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Attachment;
import org.opencds.cqf.cql.engine.debug.DebugMap;
import org.opencds.cqf.cql.engine.retrieve.RetrieveProvider;
import org.opencds.cqf.ruler.plugin.cpg.CpgProperties;
import org.opencds.cqf.ruler.plugin.cql.JpaFhirRetrieveProvider;
import org.opencds.cqf.ruler.plugin.cpg.r4.util.R4ApelonFhirTerminologyProvider;
import org.opencds.cqf.ruler.plugin.cpg.r4.util.R4BundleLibraryContentProvider;
import org.opencds.cqf.ruler.plugin.cpg.r4.util.FhirMeasureBundler;
import org.opencds.cqf.cql.evaluator.engine.retrieve.PriorityRetrieveProvider;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.apache.commons.lang3.tuple.Pair;
import org.cqframework.cql.cql2elm.ModelManager;
import org.cqframework.cql.elm.execution.VersionedIdentifier;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Endpoint;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;
import org.opencds.cqf.cql.engine.data.CompositeDataProvider;
import org.opencds.cqf.cql.engine.data.DataProvider;
import org.opencds.cqf.cql.engine.execution.CqlEngine;
import org.opencds.cqf.cql.engine.execution.EvaluationResult;
import org.opencds.cqf.cql.engine.execution.LibraryLoader;
import org.opencds.cqf.cql.engine.fhir.model.FhirModelResolver;
import org.opencds.cqf.cql.engine.fhir.model.R4FhirModelResolver;
import org.opencds.cqf.cql.engine.fhir.retrieve.RestFhirRetrieveProvider;
import org.opencds.cqf.cql.engine.fhir.searchparam.SearchParameterResolver;
import org.opencds.cqf.cql.engine.fhir.terminology.R4FhirTerminologyProvider;
import org.opencds.cqf.cql.engine.runtime.DateTime;
import org.opencds.cqf.cql.engine.runtime.Interval;
import org.opencds.cqf.cql.engine.terminology.TerminologyProvider;
import org.opencds.cqf.cql.evaluator.cql2elm.content.LibraryContentProvider;
import org.opencds.cqf.cql.evaluator.engine.execution.CacheAwareLibraryLoaderDecorator;
import org.opencds.cqf.cql.evaluator.engine.execution.TranslatingLibraryLoader;
import org.opencds.cqf.cql.evaluator.engine.retrieve.BundleRetrieveProvider;
import org.opencds.cqf.ruler.api.OperationProvider;
import org.opencds.cqf.ruler.plugin.cql.JpaTerminologyProviderFactory;
import org.opencds.cqf.ruler.plugin.utility.ClientUtilities;
import org.opencds.cqf.ruler.plugin.utility.OperatorUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class LibraryEvaluationProvider implements LibraryResolutionProvider<Library>, OperationProvider, ClientUtilities, OperatorUtilities {

	private static final Logger log = LoggerFactory.getLogger(LibraryEvaluationProvider.class);

	@Autowired
	private DaoRegistry registry;

	@Autowired
	private CpgProperties cpgProperties;

	@Autowired
	private LibraryResourceProvider libraryResourceProvider;

	@Autowired
	JpaTerminologyProviderFactory jpaTerminologyProviderFactory;

	@Autowired
	private CqlTranslatorOptions cqlTranslatorOptions;

	@Autowired
	private ModelManager modelManager;


	private LibraryResolutionProvider<org.hl7.fhir.r4.model.Library> getLibraryResourceProvider() {
		return this;
	}

	@SuppressWarnings({"unchecked", "rawtypes" })
	@Operation(name = "$evaluate", idempotent = true, type = Library.class)
	public Bundle evaluate(@IdParam IdType theId, @OperationParam(name = "patientId") String patientId,
								  @OperationParam(name = "periodStart") String periodStart,
								  @OperationParam(name = "periodEnd") String periodEnd,
								  @OperationParam(name = "productLine") String productLine,
								  @OperationParam(name = "terminologyEndpoint") Endpoint terminologyEndpoint,
								  @OperationParam(name = "dataEndpoint") Endpoint dataEndpoint,
								  @OperationParam(name = "context") String contextParam,
								  @OperationParam(name = "executionResults") String executionResults,
								  @OperationParam(name = "parameters") Parameters parameters,
								  @OperationParam(name = "additionalData") Bundle additionalData) {

		log.info("Lib evaluation stated..");
		if (patientId == null && contextParam != null && contextParam.equals("Patient")) {
			log.error("Patient id null");
			throw new IllegalArgumentException("Must specify a patientId when executing in Patient context.");
		}

		Bundle libraryBundle = new Bundle();
		Library theResource = null;
		if (additionalData != null) {
			for (Bundle.BundleEntryComponent entry : additionalData.getEntry()) {
				if (entry.getResource().fhirType().equals("Library")) {
					libraryBundle.addEntry(entry);
					if (entry.getResource().getIdElement().equals(theId)) {
						theResource = (Library) entry.getResource();
					}
				}
			}
		}

		if (theResource == null) {
			theResource = this.libraryResourceProvider.getDao().read(theId);
		}

		VersionedIdentifier libraryIdentifier = new VersionedIdentifier().withId(theResource.getName())
			.withVersion(theResource.getVersion());

		FhirModelResolver resolver = new R4FhirModelResolver();
		TerminologyProvider terminologyProvider;

		if (terminologyEndpoint != null) {
			IGenericClient client = this.createClient(resolver.getFhirContext(), terminologyEndpoint);
			if (terminologyEndpoint.getAddress().contains("apelon")) {
				terminologyProvider = new R4ApelonFhirTerminologyProvider(client);
			} else {
				terminologyProvider = new R4FhirTerminologyProvider(client);
			}
		} else {
			terminologyProvider = jpaTerminologyProviderFactory.create(new SystemRequestDetails());
		}

		DataProvider dataProvider;
		if (dataEndpoint != null) {
			List<RetrieveProvider> retrieveProviderList = new ArrayList<>();
			IGenericClient client = this.createClient(resolver.getFhirContext(), dataEndpoint);
			RestFhirRetrieveProvider retriever = new RestFhirRetrieveProvider(new SearchParameterResolver(resolver.getFhirContext()), client);
			retriever.setTerminologyProvider(terminologyProvider);
			if (terminologyEndpoint == null ||(terminologyEndpoint != null && !terminologyEndpoint.getAddress().equals(dataEndpoint.getAddress()))) {
				retriever.setExpandValueSets(true);
			}
			retrieveProviderList.add(retriever);

			if (additionalData != null) {
				BundleRetrieveProvider bundleProvider = new BundleRetrieveProvider(resolver.getFhirContext(), additionalData);
				bundleProvider.setTerminologyProvider(terminologyProvider);
				retrieveProviderList.add(bundleProvider);
				PriorityRetrieveProvider priorityProvider = new PriorityRetrieveProvider(retrieveProviderList);
				dataProvider = new CompositeDataProvider(resolver, priorityProvider);
			}
			else
			{
				dataProvider = new CompositeDataProvider(resolver, retriever);
			}


		} else {
			List<RetrieveProvider> retrieveProviderList = new ArrayList<>();
			JpaFhirRetrieveProvider retriever = new JpaFhirRetrieveProvider(this.registry,
				new SearchParameterResolver(resolver.getFhirContext()));
			retriever.setTerminologyProvider(terminologyProvider);
			// Assume it's a different server, therefore need to expand.
			if (terminologyEndpoint != null) {
				retriever.setExpandValueSets(true);
			}
			retrieveProviderList.add(retriever);

			if (additionalData != null) {
				BundleRetrieveProvider bundleProvider = new BundleRetrieveProvider(resolver.getFhirContext(), additionalData);
				bundleProvider.setTerminologyProvider(terminologyProvider);
				retrieveProviderList.add(bundleProvider);
				PriorityRetrieveProvider priorityProvider = new PriorityRetrieveProvider(retrieveProviderList);
				dataProvider = new CompositeDataProvider(resolver, priorityProvider);
			}
			else
			{
				dataProvider = new CompositeDataProvider(resolver, retriever);
			}
		}


		org.opencds.cqf.cql.evaluator.cql2elm.content.LibraryContentProvider bundleLibraryProvider = new R4BundleLibraryContentProvider(libraryBundle);
		org.opencds.cqf.cql.evaluator.cql2elm.content.LibraryContentProvider sourceProvider =  new ca.uhn.fhir.cql.common.provider.LibraryContentProvider<Library, Attachment>(
			this.getLibraryResourceProvider(), x -> x.getContent(), x -> x.getContentType(), x -> x.getData());

		List<LibraryContentProvider> sourceProviders = Arrays.asList(bundleLibraryProvider, sourceProvider);

		LibraryLoader libraryLoader = new CacheAwareLibraryLoaderDecorator(new TranslatingLibraryLoader(modelManager, sourceProviders, cqlTranslatorOptions));

		CqlEngine engine = new CqlEngine(libraryLoader, Collections.singletonMap("http://hl7.org/fhir", dataProvider), terminologyProvider);

		Map<String, Object> resolvedParameters = new HashMap<>();

		if (parameters != null) {
			for (Parameters.ParametersParameterComponent pc : parameters.getParameter()) {
				resolvedParameters.put(pc.getName(), pc.getValue());
			}
		}

		if (periodStart != null && periodEnd != null) {
			// resolve the measurement period
			Interval measurementPeriod = new Interval(this.resolveRequestDate(periodStart, true), true,
				this.resolveRequestDate(periodEnd, false), true);

			resolvedParameters.put("Measurement Period",
				new Interval(DateTime.fromJavaDate((Date) measurementPeriod.getStart()), true,
					DateTime.fromJavaDate((Date) measurementPeriod.getEnd()), true));
		}

		if (productLine != null) {
			resolvedParameters.put("Product Line", productLine);
		}

		EvaluationResult evalResult = engine.evaluate(libraryIdentifier, null,
			Pair.of(contextParam != null ? contextParam : "Unspecified", patientId == null ? "null" : patientId),
			resolvedParameters, this.getDebugMap());

		List<Resource> results = new ArrayList<>();
		FhirMeasureBundler bundler = new FhirMeasureBundler();

		if (evalResult != null && evalResult.expressionResults != null) {
			for (Map.Entry<String, Object> def : evalResult.expressionResults.entrySet()) {

				Parameters result = new Parameters();

				try {
					result.setId(def.getKey());
					Object res = def.getValue();
					// String location = String.format("[%d:%d]",
					// locations.get(def.getName()).get(0),
					// locations.get(def.getName()).get(1));
					// result.addParameter().setName("location").setValue(new StringType(location));

					// Object res = def instanceof org.cqframework.cql.elm.execution.FunctionDef
					// ? "Definition successfully validated"
					// : def.getExpression().evaluate(context);

					if (res == null) {
						result.addParameter().setName("value").setValue(new StringType("null"));
					} else if (res instanceof List<?>) {
						if (((List<?>) res).size() > 0 && ((List<?>) res).get(0) instanceof Resource) {
							if (executionResults != null && executionResults.equals("Summary")) {
								result.addParameter().setName("value")
									.setValue(new StringType(((Resource) ((List<?>) res).get(0)).getIdElement()
										.getResourceType() + "/"
										+ ((Resource) ((List<?>) res).get(0)).getIdElement().getIdPart()));
							} else {
								result.addParameter().setName("value").setResource(bundler.bundle((Iterable<Resource>) res));
							}
						} else {
							result.addParameter().setName("value").setValue(new StringType(res.toString()));
						}
					} else if (res instanceof Iterable) {
						result.addParameter().setName("value").setResource(bundler.bundle((Iterable<Resource>) res));
					} else if (res instanceof Resource) {
						if (executionResults != null && executionResults.equals("Summary")) {
							result.addParameter().setName("value")
								.setValue(new StringType(((Resource) res).getIdElement().getResourceType() + "/"
									+ ((Resource) res).getIdElement().getIdPart()));
						} else {
							result.addParameter().setName("value").setResource((Resource) res);
						}
					} else if (res instanceof Type) {
						result.addParameter().setName("value").setValue((Type) res);
					} else {
						result.addParameter().setName("value").setValue(new StringType(res.toString()));
					}

					result.addParameter().setName("resultType").setValue(new StringType(resolveType(res)));
				} catch (RuntimeException re) {
					re.printStackTrace();

					String message = re.getMessage() != null ? re.getMessage() : re.getClass().getName();
					result.addParameter().setName("error").setValue(new StringType(message));
				}
				results.add(result);
			}
		}

		return bundler.bundle(results);
	}

	private String resolveType(Object result) {
		String type = result == null ? "Null" : result.getClass().getSimpleName();
		switch (type) {
			case "BigDecimal":
				return "Decimal";
			case "ArrayList":
				return "List";
			case "FhirBundleCursor":
				return "Retrieve";
		}
		return type;
	}

	@Override
	public void update(Library library) {
		this.libraryResourceProvider.getDao().update(library);
	}

	@Override
	public Library resolveLibraryById(String libraryId, RequestDetails requestDetails) {
		try {
			return this.libraryResourceProvider.getDao().read(new IdType(libraryId));
		} catch (Exception e) {
			throw new IllegalArgumentException(String.format("Could not resolve library id %s", libraryId));
		}
	}

	@Override
	public Library resolveLibraryByName(String libraryName, String libraryVersion) {
		Iterable<org.hl7.fhir.r4.model.Library> libraries = getLibrariesByName(libraryName);
		org.hl7.fhir.r4.model.Library library = LibraryResolutionProvider.selectFromList(libraries, libraryVersion,
			x -> x.getVersion());

		if (library == null) {
			throw new IllegalArgumentException(String.format("Could not resolve library name %s", libraryName));
		}

		return library;
	}

	@Override
	public Library resolveLibraryByCanonicalUrl(String url, RequestDetails requestDetails) {
		Objects.requireNonNull(url, "url must not be null");

		String[] parts = url.split("\\|");
		String resourceUrl = parts[0];
		String version = null;
		if (parts.length > 1) {
			version = parts[1];
		}

		SearchParameterMap map = SearchParameterMap.newSynchronous();
		map.add("url", new UriParam(resourceUrl));
		if (version != null) {
			map.add("version", new TokenParam(version));
		}

		ca.uhn.fhir.rest.api.server.IBundleProvider bundleProvider = this.libraryResourceProvider.getDao().search(map);

		if (bundleProvider != null && bundleProvider.size() == 0) {
			return null;
		}
		List<IBaseResource> resourceList = bundleProvider.getAllResources();
		return  LibraryResolutionProvider.selectFromList(resolveLibraries(resourceList), version, x -> x.getVersion());
	}

	private Iterable<org.hl7.fhir.r4.model.Library> getLibrariesByName(String name) {
		// Search for libraries by name
		SearchParameterMap map = SearchParameterMap.newSynchronous();
		map.add("name", new StringParam(name, true));
		ca.uhn.fhir.rest.api.server.IBundleProvider bundleProvider = this.libraryResourceProvider.getDao().search(map);

		if (bundleProvider != null && bundleProvider.size() == 0) {
			return new ArrayList<>();
		}
		List<IBaseResource> resourceList = bundleProvider.getAllResources();
		return resolveLibraries(resourceList);
	}

	private Iterable<org.hl7.fhir.r4.model.Library> resolveLibraries(List<IBaseResource> resourceList) {
		List<org.hl7.fhir.r4.model.Library> ret = new ArrayList<>();
		for (IBaseResource res : resourceList) {
			Class<?> clazz = res.getClass();
			ret.add((org.hl7.fhir.r4.model.Library) clazz.cast(res));
		}
		return ret;
	}

	// TODO: Merge this into the evaluator
	@SuppressWarnings("unused")
	private Map<String, List<Integer>> getLocations(org.hl7.elm.r1.Library library) {
		Map<String, List<Integer>> locations = new HashMap<>();

		if (library.getStatements() == null)
			return locations;

		for (org.hl7.elm.r1.ExpressionDef def : library.getStatements().getDef()) {
			int startLine = def.getTrackbacks().isEmpty() ? 0 : def.getTrackbacks().get(0).getStartLine();
			int startChar = def.getTrackbacks().isEmpty() ? 0 : def.getTrackbacks().get(0).getStartChar();
			List<Integer> loc = Arrays.asList(startLine, startChar);
			locations.put(def.getName(), loc);
		}

		return locations;
	}

	public DebugMap getDebugMap() {
		DebugMap debugMap = new DebugMap();
		if (cpgProperties.getLogEnabled()) {
			debugMap.setIsLoggingEnabled(true);
		}
		return debugMap;
	}
}
