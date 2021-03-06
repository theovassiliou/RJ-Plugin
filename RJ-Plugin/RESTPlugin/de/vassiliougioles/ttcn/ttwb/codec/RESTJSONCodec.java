package de.vassiliougioles.ttcn.ttwb.codec;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.etsi.ttcn.tci.BooleanValue;
import org.etsi.ttcn.tci.CharstringValue;
import org.etsi.ttcn.tci.FloatValue;
import org.etsi.ttcn.tci.IntegerValue;
import org.etsi.ttcn.tci.RecordOfValue;
import org.etsi.ttcn.tci.RecordValue;
import org.etsi.ttcn.tci.TciCDProvided;
import org.etsi.ttcn.tci.TciTypeClass;
import org.etsi.ttcn.tci.Type;
import org.etsi.ttcn.tci.UnionValue;
import org.etsi.ttcn.tci.UniversalCharstringValue;
import org.etsi.ttcn.tci.Value;
import org.etsi.ttcn.tri.TriMessage;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.testingtech.ttcn.tci.codec.base.AbstractBaseCodec;
import com.testingtech.ttcn.tciext.ExtendedStringValue;
import com.testingtech.ttcn.tri.TriMessageImpl;

import de.vassiliougioles.ttcn.ttwb.port.TTCNRESTMapping;

/**
 * @author Theo Vassiliou
 *
 */
public class RESTJSONCodec extends AbstractBaseCodec implements TTCNRESTMapping, TciCDProvided {

	static private JSONParser parser = new JSONParser();
	private String baseURL = null;
	private String authorization = null;

	public void setBaseUrl(String baseUrl) {
		this.baseURL = baseUrl;
	}
	
	public String getBaseUrl() {
		return this.baseURL;
	}

	public void setAuthorization(String authorization) {
		this.authorization = authorization;
	}

	private Value decodeResponseMessage(UnionValue uv, ResponseMessage rm) throws ParseException {
		return uv;
	}

	private Value decodeResponseMessage(RecordValue rv, ResponseMessage rm) throws ParseException {
		if (rv.getType().getTypeEncoding().equals(_HTTP_RESPONSE_ENCODING_NAME_)) {
			rv.setField("statusLine", decodeStatusLine(rv.getField("statusLine"), rm));
			rv.setField("headers", decodeHeader(rv.getField("headers"), rm));
			rv.setField("body", decodeBody(rv.getField("body"), rm));

			return rv;
		}

		
		if (rv.getValueEncoding().equals(_BODY_FIELD_JSON_ENCODING_NAME_)) {
			Object obj;
			obj = parser.parse(rm.getContent());
			if (obj == null)
				return rv; // Can't create a JSON object
			Value mappedObject = mapJSON(rv, obj);
			if (mappedObject != null)
				rv = (RecordValue) mappedObject;
			return rv;
		}
		
		
		String[] responseFieldsNames = rv.getFieldNames();
		HttpFields headers = rm.getHeaderFields();
		for (int i = 0; i < responseFieldsNames.length; i++) {
			Value aField = rv.getField(responseFieldsNames[i]);
			if (aField.getValueEncoding().equals(_BODY_FIELD_JSON_ENCODING_NAME_)) {
				Object obj;
				if (rm.getContent() != null) {
					obj = parser.parse(rm.getContent());
					if (obj == null)
						continue; // Can't create a JSON object
					Value rov = (Value) rv.getField(responseFieldsNames[i]);
					Value mappedObject = mapJSON(rov, obj);
					if (mappedObject == null) {
						rv.setFieldOmitted(responseFieldsNames[i]);
						continue;
					}
					rv.setField(responseFieldsNames[i], mappedObject);
				} else {
					rv.setFieldOmitted(responseFieldsNames[i]);
				}
			} else if (aField.getValueEncoding().startsWith(_HEADER_FIELD_ENCODING_PREFIX_)) {
				String headerSpec = aField.getValueEncoding();
				String[] headerSplit = aField.getValueEncoding().split(":");

				if (headerSplit.length > 1) {
					headerSpec = headerSplit[1].trim();
				} else {
					headerSpec = responseFieldsNames[i];
				}
				HttpField hField = headers.getField(headerSpec);

				if (hField == null) {
					rv.setFieldOmitted(responseFieldsNames[i]);
					continue; // Header not included
				}

				String headerValue = hField.getValue();
				if (headerValue != null) {
					// TODO: Check that the variant indicates string encoding
					((UniversalCharstringValue) aField).setString(headerValue);
					rv.setField(responseFieldsNames[i], aField);
				}
			} else {
				return rv;
			}
		}

		return rv;
	}

	private Value decodeResponseMessage(RecordOfValue rov, ResponseMessage rm) throws ParseException {

		// if we are here we are a recordOf and we try to fit body of the response
		// message
		// it should be
		// a) a JSON Object
		// b) an Array
		// if it is not all of this then it can't be decoded into a recordOf

		if (rov.getValueEncoding().equals(_BODY_FIELD_JSON_ENCODING_NAME_)) {
			Object obj;
			if (rm.getContent() != null) {
				obj = parser.parse(rm.getContent());
				if (obj == null)
					return rov; // Can't create a JSON object
				Value mappedObject = mapJSON(rov, obj);
				if (mappedObject == null)
					return null;
				return rov;
			} else {
				return rov;
			}
		}
		return null;
	}

	private Value decodeResponseMessage(Value v, ResponseMessage rm) throws ParseException {
		int typeClass = v.getType().getTypeClass();
		switch (typeClass) {
		case TciTypeClass.UNION:
			return decodeResponseMessage((UnionValue) v, rm);
		case TciTypeClass.RECORD:
		case TciTypeClass.SET:
			return decodeResponseMessage((RecordValue) v, rm);
		case TciTypeClass.RECORD_OF:
		case TciTypeClass.SET_OF:
			return decodeResponseMessage((RecordOfValue) v, rm);
		default:
			break;
		}
		return v;
	}

	@Override
	public synchronized Value decode(TriMessage rcvdMessage, Type decodingHypothesis) {
		ResponseMessage rm = new ResponseMessage();
		HttpParser hParser = new HttpParser(rm);
		ByteBuffer bb = ByteBuffer.wrap(rcvdMessage.getEncodedMessage());
		// FIXME: This hack is required due to a bug introduced in with TTwb 27 or
		// later.
		// decodingHypothesis.getEncoding() returns errornously the module encoding, and
		// not the one
		// attached to the type.
		String typeEncoding = decodingHypothesis.newInstance().getValueEncoding();
		if (typeEncoding == null) {
			return super.decode(rcvdMessage, decodingHypothesis);
		}

		while (!hParser.isComplete() && bb.remaining() > 0) {
			hParser.parseNext(bb);
		}

		if ((typeEncoding.equals(_REST_RESPONSE_ENCODING_NAME_) || typeEncoding.equals(_GET_RESPONSE_ENCODING_NAME_) || // NOTE
																														// kept
																														// for
																														// backward
																														// compatibility
				typeEncoding.equals(_POST_RESPONSE_ENCODING_NAME_) ||
				typeEncoding.equals(_DELETE_RESPONSE_ENCODING_NAME_))) {
			try {

				// So we should be able to decode this message.
				// We have the following options.
				// Union or Record
				if (decodingHypothesis.getTypeClass() == TciTypeClass.UNION) {
					// Union: Support for different response codes. fieldName == "_statusCode"

					// #1: Look whether we have a field name, matching the statusCode
					String fieldName = fieldNameByStatusCode(decodingHypothesis, rm, rm.getStatusCode());
					if (fieldName == null)
						return null;
					// #2: Decode Response
					UnionValue uv = (UnionValue) decodingHypothesis.newInstance();
					Value variant = uv.getVariant(fieldName);
					decodeResponseMessage(variant, rm);
					uv.setVariant(fieldName, variant);
					// #3: Set Variant as variant in Union
					return uv;
				} else if (decodingHypothesis.getTypeClass() == TciTypeClass.RECORD || decodingHypothesis.getTypeClass() == TciTypeClass.SET) {
					// Record: Only body without response code.
					// #1: Decode Response
					return decodeResponseMessage(decodingHypothesis.newInstance(), rm);
				} else {
					super.decode(rcvdMessage, decodingHypothesis);
				}

				return null;
			} catch (ParseException e) {
				// Well, if we can't parse it, we can't parse it.
				return null;
			}
		} else if (typeEncoding.equals(_HTTP_RESPONSE_ENCODING_NAME_)) {
			RecordValue httpResponseValue = (RecordValue) decodingHypothesis.newInstance();
			try {
				decodeResponseMessage(httpResponseValue, rm);
			} catch (ParseException e) {
				// Well, if we can't parse it, we can't parse it.
				return null;

			}
			return httpResponseValue;
		}
		return super.decode(rcvdMessage, decodingHypothesis);
	}

	/**
	 * Returns an existing fieldName based on the statusCode
	 * 
	 * @param decodingHypothesis
	 * @param rm
	 * @param statusCode
	 * @return the fieldName matching the statusCode, null if not present, or if
	 *         type does not have fields
	 */
	private String fieldNameByStatusCode(Type decodingHypothesis, ResponseMessage rm, int statusCode) {
		int typeClass = decodingHypothesis.getTypeClass();

		if (typeClass != TciTypeClass.RECORD && typeClass != TciTypeClass.SET && typeClass != TciTypeClass.UNION)
			return null;
		String strStatusCode = Integer.toString(statusCode);

		switch (typeClass) {
		case TciTypeClass.RECORD:
		case TciTypeClass.SET:
			RecordValue rv = (RecordValue) decodingHypothesis.newInstance();
			String[] recordFieldNames = rv.getFieldNames();
			for (String fm : recordFieldNames) {
				if (!fm.startsWith("_" + strStatusCode)) {
					continue;
				}
				return fm;
			}
			break;
		case TciTypeClass.UNION:
			UnionValue uv = (UnionValue) decodingHypothesis.newInstance();
			String[] unionFieldNames = uv.getVariantNames();
			for (String fm : unionFieldNames) {
				if (!fm.startsWith("_" + strStatusCode)) {
					continue;
				}
				return fm;
			}
			break;
		default:
			break;
		}
		return null;
	}

	private Value decodeHeader(Value _headers, ResponseMessage rm) {
		RecordOfValue headers = (RecordOfValue) _headers;

		HttpFields httpFields = rm.getHeaderFields();
		int i = 0;
		for (HttpField httpField : httpFields) {
			RecordValue header = (RecordValue) headers.getElementType().newInstance();
			UniversalCharstringValue name = (UniversalCharstringValue) header.getField("name");
			UniversalCharstringValue val = (UniversalCharstringValue) header.getField("val");
			name.setString(httpField.getName());
			val.setString(httpField.getValue());

			header.setField("name", name);
			header.setField("val", val);

			headers.setField(i++, header);
		}

		return _headers;
	}

	private Value decodeBody(Value _body, ResponseMessage rm) {
		RecordValue body = (RecordValue) _body;
		UniversalCharstringValue messageBodyTxt = (UniversalCharstringValue) body.getField("messageBodyTxt");
		if (rm.getContent() != null) {
			messageBodyTxt.setString(rm.getContent());
			body.setField("messageBodyTxt", messageBodyTxt);
			body.setField("messageBodyRaw", newOctetstringValue(rm.getContent().getBytes(StandardCharsets.UTF_8)));
		}
		return _body;
	}

	private Value decodeStatusLine(Value _statusLine, ResponseMessage rm) {
		RecordValue statusLine = (RecordValue) _statusLine;
		IntegerValue statusCode = (IntegerValue) statusLine.getField("statusCode");
		UniversalCharstringValue reasonPhrase = (UniversalCharstringValue) statusLine.getField("reasonPhrase");

		statusCode.setInt(rm.getStatusCode());
		statusLine.setField("statusCode", statusCode);

		// REASON Phrase is optional
		if (rm.getReasonPhrase() != null) {
			reasonPhrase.setString(rm.getReasonPhrase());
			statusLine.setField("reasonPhrase", reasonPhrase);
		}
		return _statusLine;
	}

	private String[] getMappedFieldNames(RecordValue rv) {

		String[] result = (String[]) Array.newInstance(String.class, rv.getFieldNames().length);

		String[] allFields = rv.getFieldNames();
		for (int i = 0; i < allFields.length; i++) {

			// check whether we have a name mapping via encode(fieldName) attribute
			String mappedFieldName = rv.getField(allFields[i]).getValueEncodingVariant() == null ? allFields[i]
					: rv.getField(allFields[i]).getValueEncodingVariant();

			Array.set(result, i, mappedFieldName);
		}

		return result;

	}

	private Value mapJSON(Value field, Object obj) {
		JSONObject jo;
		JSONArray ja;

		switch (field.getType().getTypeClass()) {
		case TciTypeClass.RECORD_OF:
		case TciTypeClass.SET_OF:
		case TciTypeClass.ARRAY:
			// map an array
			if (!(obj instanceof JSONArray))
				return null;
			ja = (JSONArray) obj;
			RecordOfValue rov = ((RecordOfValue) field);
			for (int i = 0; i < ja.size(); i++) {
				Value val = rov.getElementType().newInstance();
				// FIXME: Add some error handling
				mapJSON(val, ((JSONArray) obj).get(i));
				rov.appendField(val);
			}
			break;

		case TciTypeClass.SET:
		case TciTypeClass.RECORD:
			jo = (JSONObject) obj;
			RecordValue rv = (RecordValue) field;
			String[] allFieldsMapped = getMappedFieldNames(rv);
			String[] allFields = rv.getFieldNames();
			for (int i = 0; i < allFields.length; i++) {
				if (jo.get(allFieldsMapped[i]) != null) {
					rv.setField(allFields[i],
							mapJSON(rv.getField(allFields[i]).getType().newInstance(), jo.get(allFieldsMapped[i])));
				} else {
					// We found a mapping mismatch. No JSON object for this field allFields[i]
					rv.setFieldOmitted(allFields[i]);
					continue;
				}
			}
			break;
		case TciTypeClass.BITSTRING:
		case TciTypeClass.OCTETSTRING:
		case TciTypeClass.HEXSTRING:
			// map a string
			// map an Object
			// FIXME use base64Decode on the string
			((ExtendedStringValue) field).setBytes(obj.toString().getBytes());
			break;
		case TciTypeClass.CHARSTRING:
		case TciTypeClass.UNIVERSAL_CHARSTRING:
			// map a string
			// map an Object
			((ExtendedStringValue) field).setString(obj.toString());
			break;
		case TciTypeClass.FLOAT:
			((FloatValue) field).setFloat(Float.parseFloat(obj.toString()));
			break;
		case TciTypeClass.INTEGER:
			try {
				((IntegerValue) field).setInt(Integer.parseInt(obj.toString()));
			} catch (NumberFormatException nex) {
				((IntegerValue) field).setBigInt(new BigInteger(obj.toString()));
			}
			break;
		case TciTypeClass.BOOLEAN:
			((BooleanValue) field).setBoolean(Boolean.parseBoolean(obj.toString()));
			break;

		default:
			logError("No support of type " + field.getType().getName() + " in mapJSON(). Fix me!");
			break;

		}

		return field;

	}

	@Override
	public synchronized TriMessage encode(Value sendMessage) {
		if (sendMessage.getType() == null
				|| !sendMessage.getType().getTypeEncoding().startsWith(_ENCODING_NAME_PREFIX_)) {
			return super.encode(sendMessage);
		}
		StringBuilder dumpMessage = new StringBuilder();

		RecordValue restMsg = (RecordValue) sendMessage;

		// Pass 1: Build Endpoint by replacing {} with field-values
		String endPoint = constructEndpoint(restMsg);
		// Pass 2: Collect all param-encoded field, deep
		List<ParamField> params = ParamField.collectParams(restMsg, null);
		// Pass 3: Build (additional) query params
		String queryParams = ParamField.buildQueryParams(params);
		// Concat (1) and (3)
		String strEndpoint = saveURLConcat(getBaseUrl(), endPoint, queryParams);
		// Instantiate HttpClient
		HttpClient httpClient = new HttpClient(new SslContextFactory());
		// Configure HttpClient, for example:
		httpClient.setFollowRedirects(false);

		List<HeaderField> headers = HeaderField.collectHeaders(restMsg, null);
		
		// check whether encode is one of the supported
		if (sendMessage.getType().getTypeEncoding().equals(_GET_ENCODING_NAME_)) {
			try {
				Request request = createRequest(httpClient, "GET", authorization, strEndpoint, dumpMessage, headers);
			} catch (Exception e1) {
				e1.printStackTrace();
				tciErrorReq(e1.getMessage());
				return null;
			}

			return TriMessageImpl.valueOf(dumpMessage.toString().getBytes(StandardCharsets.UTF_8));

		} else if (sendMessage.getType().getTypeEncoding().equals(_POST_ENCODING_NAME_)) {
			try {
				Request request = createRequest(httpClient, "POST", authorization, strEndpoint, dumpMessage, headers);
				request.content(new StringContentProvider(createBody(restMsg), "UTF-8"), _CONTENT_JSON_ENCODING_);
				dumpMessage.append("\n" + createBody(restMsg));

			} catch (Exception e1) {
				tciErrorReq(e1.getMessage());
				return null;
			}

			return TriMessageImpl.valueOf(dumpMessage.toString().getBytes(StandardCharsets.UTF_8));
		} else if (sendMessage.getType().getTypeEncoding().equals(_HEAD_ENCODING_NAME_)) {
			try {
				Request request = createRequest(httpClient, "HEAD", authorization, strEndpoint, dumpMessage, headers);
			} catch (Exception e1) {
				e1.printStackTrace();
				tciErrorReq(e1.getMessage());
				return null;
			}

			return TriMessageImpl.valueOf(dumpMessage.toString().getBytes(StandardCharsets.UTF_8));
		} else if (sendMessage.getType().getTypeEncoding().equals(_OPTIONS_ENCODING_NAME_)) {
			try {
				Request request = createRequest(httpClient, "OPTIONS", authorization, strEndpoint, dumpMessage,
						headers);
				request.content(new StringContentProvider(createBody(restMsg), "UTF-8"), _CONTENT_JSON_ENCODING_);
				dumpMessage.append("\n" + createBody(restMsg));

			} catch (Exception e1) {
				tciErrorReq(e1.getMessage());
				return null;
			}

			return TriMessageImpl.valueOf(dumpMessage.toString().getBytes(StandardCharsets.UTF_8));
		} else if (sendMessage.getType().getTypeEncoding().equals(_PUT_ENCODING_NAME_)) {
			try {
				Request request = createRequest(httpClient, "PUT", authorization, strEndpoint, dumpMessage, headers);
				request.content(new StringContentProvider(createBody(restMsg), "UTF-8"), _CONTENT_JSON_ENCODING_);
				dumpMessage.append("\n" + createBody(restMsg));

			} catch (Exception e1) {
				tciErrorReq(e1.getMessage());
				return null;
			}

			return TriMessageImpl.valueOf(dumpMessage.toString().getBytes(StandardCharsets.UTF_8));
		} else if (sendMessage.getType().getTypeEncoding().equals(_DELETE_ENCODING_NAME_)) {
			try {
				Request request = createRequest(httpClient, "DELETE", authorization, strEndpoint, dumpMessage, headers);
				String body = createBody(restMsg);
				if (body != null) {
					request.content(new StringContentProvider(body, "UTF-8"), _CONTENT_JSON_ENCODING_);
				dumpMessage.append("\n" + body);
				} else {
					dumpMessage.append("\n");
				}
			} catch (Exception e1) {
				tciErrorReq(e1.getMessage());
				return null;
			}

			return TriMessageImpl.valueOf(dumpMessage.toString().getBytes(StandardCharsets.UTF_8));
		} else if (sendMessage.getType().getTypeEncoding().equals(_PATCH_ENCODING_NAME_)) {
			try {
				Request request = createRequest(httpClient, "PATCH", authorization, strEndpoint, dumpMessage, headers);
				request.content(new StringContentProvider(createBody(restMsg), "UTF-8"), _CONTENT_JSON_ENCODING_);
				dumpMessage.append("\n" + createBody(restMsg));

			} catch (Exception e1) {
				tciErrorReq(e1.getMessage());
				return null;
			}

			return TriMessageImpl.valueOf(dumpMessage.toString().getBytes(StandardCharsets.UTF_8));
		}

		return super.encode(sendMessage);
	}

	public String createJSONBody(Value bodyField) {
		String theJSON = null;
		if (bodyField != null) {
			theJSON = TTCN2JSONencode(bodyField);
		}
		return theJSON;
	}

	public String createFormDataBody(Value bodyField) {
		String theJSON = null;
		if (bodyField != null) {
			theJSON = TTCN2JSONencode(bodyField);
		}
		return theJSON;
	}

	public String createBody(RecordValue restMessage) {
		String theJSON = null;

		Value fieldToEncode = null;
		String[] fieldNames = restMessage.getFieldNames();
		for (int i = 0; i < fieldNames.length; i++) {
			String string = fieldNames[i];
			Value field = restMessage.getField(string);
			if (field.getValueEncoding().equals(_BODY_FIELD_JSON_ENCODING_NAME_)) {
				return createJSONBody(field);
			} else if (field.getValueEncoding().startsWith(_BODY_FORMDATA_FIELD_ENCODING_PREFIX_)) {
				// FIXME: Continue here
				return createFormDataBody(field);
			}
		}
		return theJSON;
	}

	public void encodeResponseMessage(Response response, StringBuilder builder) {
		builder.append(response.getVersion() + " ").append(response.getStatus());
		builder.append(" ");
		if (response.getReason() != null) {
			builder.append(response.getReason());
		}

		builder.append("\n");

		HttpFields fields = response.getHeaders();

		boolean hasTransferEncoding = false;
		for (HttpField httpField : fields) {
			hasTransferEncoding = false;
			if (!httpField.getName().equals("Transfer-Encoding")) {
				builder.append(httpField.getName()).append(": ");
			}

			String[] values = httpField.getValues();
			for (int i = 0; i < values.length; i++) {
				if (httpField.getName().equals("Transfer-Encoding") && values[i].equals("chunked")) {
					hasTransferEncoding = true;
					break;
				} else if (httpField.getName().equals("Transfer-Encoding") && values[i].equals("chunked")) {
					hasTransferEncoding = true;
					logError("Unexpected Transfer-Encoding header. Value: " + values[i]);
				} else {
					builder.append(values[i]);
					if (i < values.length - 1) {
						builder.append(", ");
					}
				}
			}
			if (!hasTransferEncoding) {
				builder.append("\n");
			} else {
				if (response instanceof ContentResponse) {
					builder.append("Content-Length: ").append(((ContentResponse) response).getContent().length)
							.append("\n");
				}
			}
		}
		if (response instanceof ContentResponse) {
			builder.append("\n").append(((ContentResponse) response).getContentAsString()).append("\n");
			builder.append("\n");
		}
	}

	public String saveURLConcat(String baseURL, String endPoint, String queryParams) {
		StringBuffer sb = new StringBuffer(baseURL);

		sb.append(endPoint);
		if (queryParams.length() <= 0) {
			return sb.toString();
		}

		if (!endPoint.contains("?")) {
			sb.append("?");
		}

		sb.append(queryParams);

		return sb.toString();
	}

	private String TTCN2JSONencode(Value fieldToEncode) {
		StringBuilder builder = new StringBuilder();

		switch (fieldToEncode.getType().getTypeClass()) {
		case TciTypeClass.RECORD_OF:
			builder.append("[ ");
			RecordOfValue rov = (RecordOfValue) fieldToEncode;
			for (int i = 0; i < rov.getLength(); i++) {
				builder.append(TTCN2JSONencode(rov.getField(i)));
				if (i < rov.getLength() - 1) {
					builder.append(", ");
				}
			}
			builder.append(" ]");
			break;
		case TciTypeClass.RECORD:
		case TciTypeClass.SET:
			builder.append("{ ");
			RecordValue rv = (RecordValue) fieldToEncode;
			String[] fieldNames = rv.getFieldNames();
			for (int i = 0; i < fieldNames.length; i++) {
				if (!rv.getField(fieldNames[i]).notPresent()) {
					builder.append(String2JSON(fieldNames[i])).append(": ");
					builder.append(TTCN2JSONencode(rv.getField(fieldNames[i])));
					if (i < fieldNames.length - 1) {
						builder.append(", ");
					}
				}
			}
			builder.append(" }");
			break;
		case TciTypeClass.CHARSTRING:
			builder.append(String2JSON(((CharstringValue) fieldToEncode).getString()));
			break;
		case TciTypeClass.UNIVERSAL_CHARSTRING:
			builder.append(String2JSON(((UniversalCharstringValue) fieldToEncode).getString()));
			break;
		case TciTypeClass.INTEGER:
			builder.append(((IntegerValue) fieldToEncode).getInt());
			break;
		case TciTypeClass.FLOAT:
			builder.append(((FloatValue) fieldToEncode).getFloat());
			break;
		default:
			logError("No support of type " + fieldToEncode.getType().getName() + " in TTCN2JSONEncode(). Fix!");
		}

		return builder.toString();
	}

	private String String2JSON(String string) {
		return new String("\"" + string + "\"");
	}

	public Request createRequest(HttpClient client, String method, String authorization, String path,
			StringBuilder dumpMessage, List<HeaderField> templateHeadfields) throws Exception {
		client.start();
		Request request = null;
		switch (method) {

		case "GET":
			request = client.newRequest(path);
			break;
		case "POST":
			request = client.POST(path);
			break;
		case "HEAD":
			request = client.newRequest(path);
			method = "HEAD";
			request.method(method);
			break;
		case "OPTIONS":
			request = client.newRequest(path);
			method = "OPTIONS";
			request.method(method);
			break;
		case "PUT":
			request = client.newRequest(path);
			method = "PUT";
			request.method(method);
			break;
		case "DELETE":
			request = client.newRequest(path);
			method = "DELETE";
			request.method(method);
			break;
		case "PATCH":
			request = client.newRequest(path);
			method = "PATCH";
			request.method(method);
			break;
		default:
			logError("Unsupported method. Using GET instead");
			method = "GET";
			request = client.newRequest(path);
		}

		request = request.header("Accept", "application/json");

		if (!authorization.equals(_DEFAULT_AUTHORIZATION_)) {
			request = request.header("Authorization", authorization);
		}
		request = request.agent(_USER_AGENT_NAME_);

		dumpMessage.append(request.getMethod() + " " + request.getURI() + " " + request.getVersion() + "\n");

		for (Iterator iterator = templateHeadfields.iterator(); iterator.hasNext();) {
			HeaderField headerField = (HeaderField) iterator.next();
			request.header(headerField.getHeaderName(), ((UniversalCharstringValue) headerField.getValue()).toString());
		}

		HttpFields fields = request.getHeaders();

		boolean hasTransferEncoding = false;

		for (HttpField httpField : fields) {
			hasTransferEncoding = false;
			if (!httpField.getName().equals("Transfer-Encoding")) {
				dumpMessage.append(httpField.getName()).append(": ");
			}

			String[] values = httpField.getValues();
			for (int i = 0; i < values.length; i++) {
				if (httpField.getName().equals("Transfer-Encoding") && values[i].equals("chunked")) {
					hasTransferEncoding = true;
					break;
				} else if (httpField.getName().equals("Transfer-Encoding") && values[i].equals("chunked")) {
					hasTransferEncoding = true;
					logError("Unexpected Transfer-Encoding header. Value: " + values[i]);
				} else {
					dumpMessage.append(values[i]);
					if (i < values.length - 1) {
						dumpMessage.append(", ");
					}
				}
			}
			if (!hasTransferEncoding) {
				dumpMessage.append("\n");
			}
		}
		return request;
	}

	public String constructEndpoint(RecordValue restMessage) {

		// restMessage.encode() == _REQUEST_PATH_VARIANT_PREFIX_
		// "path: /arrivalBoard/{id}?date={date}"
		String path = restMessage.getType().getTypeEncodingVariant();
		if (!path.startsWith(_REQUEST_PATH_VARIANT_PREFIX_)) {
			logError("We only support path encoding variants for REST messages");
			return null;
		}
		// so we only have paths so far
		path = path.split(":")[1].trim();
		String instantiatedPath = replacePathParams(restMessage, path);
		if (instantiatedPath == null) {
			return "";
		}

		return instantiatedPath;
	}

	private String replacePathParams(RecordValue restMessage, String path) {
		String[] pathParams = StringUtils.substringsBetween(path, "{", "}");
		String instantiatedPath = new String(path.toString());
		if (pathParams != null) {
			for (String param : pathParams) {
				try {
					assert ((StringUtils.countMatches(restMessage.getField(param).toString(), "\"")
							% 2) == 0) : "Uneven occurence of \". FIX handling.";
					if (!((restMessage.getField(param).getType().getTypeClass() == TciTypeClass.CHARSTRING)
							|| restMessage.getField(param).getType().getTypeClass() == TciTypeClass.UNIVERSAL_CHARSTRING
							|| restMessage.getField(param).getType().getTypeClass() == TciTypeClass.INTEGER)) {
						logError("Only supporting Universal Charstring, Charstring or Integer Fields so far.");
						return null;
					}

					if (restMessage.getField(param).getType().getTypeClass() == TciTypeClass.INTEGER) {
						String bi = ((IntegerValue) restMessage.getField(param)).getBigInt().toString();
						instantiatedPath = StringUtils.replace(instantiatedPath, "{" + param + "}",
								URLEncoder.encode(bi, "UTF-8").replace("+", "%20"));
						return instantiatedPath;
					}

					String uriEncodedFieldValue = ((UniversalCharstringValue) restMessage.getField(param)).getString()
							.replace("+", "%2");
					instantiatedPath = StringUtils.replace(instantiatedPath, "{" + param + "}",
							URLEncoder.encode(uriEncodedFieldValue, "UTF-8").replace("+", "%20"));
				} catch (UnsupportedEncodingException e) {
					logError("Unsupported Encoding execption", e);
					return null;
				}
			}
		}
		return instantiatedPath;
	}

}
