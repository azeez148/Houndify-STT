package com.gabrieljimenez.houndifystt;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.*;


import com.fasterxml.jackson.databind.JsonNode;
import com.hound.android.sdk.VoiceSearch;
import com.hound.android.sdk.VoiceSearchInfo;
import com.hound.android.sdk.VoiceSearchListener;
import com.hound.android.sdk.VoiceSearchState;
import com.hound.android.sdk.audio.SimpleAudioByteStreamSource;
import com.hound.android.sdk.util.HoundRequestInfoFactory;
import com.hound.core.model.sdk.HoundRequestInfo;
import com.hound.core.model.sdk.HoundResponse;
import com.hound.core.model.sdk.PartialTranscript;
import com.hound.core.model.sdk.Disambiguation;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private Button recordButton;
    private TextView statusTextView;
    private ListView resultList;
    private TextView selectedText;

    private VoiceSearch voiceSearch;
    private LocationManager locationManager;
    private JsonNode lastConversationState;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recordButton = (Button)findViewById(R.id.Recordbutton);
        statusTextView = (TextView)findViewById(R.id.statusTextView);
        resultList = (ListView)findViewById(R.id.resultList);
        selectedText = (TextView)findViewById(R.id.selectedText);


        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        /** Setup a click listener for the search button to trigger the Voice Search */
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                if(isConnected()){
                    // No VoiceSearch is active, start one.
                    if ( voiceSearch == null ) {
                        resetUIState();
                        startSearch();
                    }
                    // Else stop the current search
                    else {
                        // voice search has already started.
                        if (voiceSearch.getState() == VoiceSearchState.STATE_STARTED) {
                            voiceSearch.stopRecording();
                        }
                        else {
                            voiceSearch.abort();
                        }

                    }
                }else {
                    Toast.makeText(getApplicationContext(), "Plese Connect to Internet", Toast.LENGTH_LONG).show();
                }

            }
        });

    }

    public  boolean isConnected()
    {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo net = cm.getActiveNetworkInfo();
        if (net!=null && net.isAvailable() && net.isConnected()) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Helper method called from the startSearch() method below to fill out user information
     * needed in the HoundRequestInfo query object sent to the Hound server.
     *
     * @return
     */
    private HoundRequestInfo getHoundRequestInfo() {
        final HoundRequestInfo requestInfo = HoundRequestInfoFactory.getDefault(this);

        // Client App is responsible for providing a UserId for their users which is meaningful to the client.
        requestInfo.setUserId("ID2");
        // Each request must provide a unique request ID.
        requestInfo.setRequestId(UUID.randomUUID().toString());
        // Providing the user's location is useful for geographic queries, such as, "Show me restaurants near me".
        setLocation( requestInfo, locationManager.getLastKnownLocation( LocationManager.PASSIVE_PROVIDER ));

        // for the first search lastConversationState will be null, this is okay.  However any future
        // searches may return us a conversation state to use.  Add it to the request info when we have one.
        requestInfo.setConversationState( lastConversationState );

        requestInfo.setMaxResults(10);
        requestInfo.setMinResults(5);
        //requestInfo.setClientMatchesOnly(false);

        return requestInfo;
    }

    /**
     * Helper function for filling the user's location info into the query.
     *
     * @param requestInfo
     * @param location
     */
    public static void setLocation(final HoundRequestInfo requestInfo, final Location location) {
        if (location != null) {
            requestInfo.setLatitude(location.getLatitude());
            requestInfo.setLongitude(location.getLongitude());
            requestInfo.setPositionHorizontalAccuracy((double) location.getAccuracy());
        }
    }

    private void resetUIState() {
        recordButton.setEnabled(true);
        recordButton.setText("Start Recording");
        selectedText.setText("");
        resultList.setAdapter(null);

    }

    /**
     * Method used to start the VoiceSearch. Called from the Record Button handler.
     */
    private void startSearch() {
        if (voiceSearch != null) {
            return; // We are already searching
        }

        /**
         * Example of using the VoiceSearch.Builder to configure a VoiceSearch object
         * which is then use to run the voice search.
         */
        voiceSearch = new VoiceSearch.Builder()
                .setRequestInfo( getHoundRequestInfo() )
                .setAudioSource( new SimpleAudioByteStreamSource() )
                .setClientId( Constants.CLIENT_ID )     // Client ID for access API
                .setClientKey( Constants.CLIENT_KEY )   // Client KEY for access API
                .setListener( voiceListener )
                .build();

        //System.out.println(voiceSearch.toString());

        statusTextView.setText("Listening...");
        // Toggle the text on our search button to indicate pressing it now will abort the search.
        recordButton.setText("Stop Recording");

        // Kickoff the search. This will start listening from the microphone and streaming
        // the audio to the Hound server, at the same time, waiting for a response which will be passed
        // back as a result to the voiceListener registered above.
        voiceSearch.start();
    }

    /**
     * Implementation of the VoiceSearchListener interface used for receiving search state information
     * and the final search results.
     */
    private final VoiceSearchListener voiceListener = new VoiceSearchListener() {

        /**
         * Called every time a new partial transcription is received from the Hound server.
         * This is used for providing feedback to the user of the server's interpretation of their query.
         *
         * @param transcript
         */
        @Override
        public void onTranscriptionUpdate(final PartialTranscript transcript) {
            final StringBuilder str = new StringBuilder();
            switch (voiceSearch.getState()) {
                case STATE_STARTED:
                    str.append("Listening...");
                    break;
                case STATE_SEARCHING:
                    str.append("Receiving...");
                    break;
                default:
                    str.append("Unknown");
                    break;
            }
            str.append("\n\n");
            str.append(transcript.getPartialTranscript());

            statusTextView.setText(str.toString());
        }

        @Override
        public void onResponse(final HoundResponse response,  final VoiceSearchInfo info) {
            voiceSearch = null;
            resetUIState();

            // Make sure the request succeeded with OK
            if ( response.getStatus().equals( HoundResponse.Status.OK ) ) {
                if (!response.getResults().isEmpty()) {
                    // Save off the conversation state.  This information will be returned to the server
                    // in the next search. Note that at some point in the future the results CommandResult list
                    // may contain more than one item. For now it does not, so just grab the first result's
                    // conversation state and use it.
                    lastConversationState = response.getResults().get(0).getConversationState();
                    //
                    //response.setNumToReturn(10);
                    //System.out.println(response.getNumToReturn());
                    //System.out.println(response.getResults().toString());
                }

                statusTextView.setText("Received response...displaying the result");

                // We put pretty printing JSON on a separate thread as the server JSON can be quite large and will stutter the UI

                // Not meant to be configuration change proof, this is just a demo
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        JSONObject jsonResponse;
                        String message;
                        try {
                            message = "Response\n\n" + new JSONObject(info.getContentBody()).toString(4);
                            //System.out.print(message);
                            //System.out.println(voiceSearch.toString());
                            jsonResponse = new JSONObject(info.getContentBody());
                        } catch (final JSONException ex) {
                            statusTextView.setText("Bad JSON\n\n" + response);
                            message = "Bad JSON\n\n" + response;
                            jsonResponse = new JSONObject();
                        }
                        final String finalMessage = message;
                        final JSONObject finalJson = jsonResponse;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                //statusTextView.setText(finalMessage);
                                try {
                                    showResponseFromHoundService(finalJson);
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                    }
                }).start();
            }
            else {
                statusTextView.setText( "Request failed with: " + response.getErrorMessage() );
            }

        }


        /**
         * Called if the search fails do to some kind of error situation.
         *
         * @param ex
         * @param info
         */
        @Override
        public void onError(final Exception ex, final VoiceSearchInfo info) {
            voiceSearch = null;
            resetUIState();
            statusTextView.setText(exceptionToString(ex));
        }

        /**
         * Called when the recording phase is completed.
         */
        @Override
        public void onRecordingStopped() {
            recordButton.setText("Receiving");
            statusTextView.setText("Receiving...");
        }

        /**
         * Called if the user aborted the search.
         *
         * @param info
         */
        @Override
        public void onAbort(final VoiceSearchInfo info) {
            voiceSearch = null;
            resetUIState();
            statusTextView.setText("Aborted");
        }
    };

    /**
     * Helper method for converting an Exception to a String
     * with stack trace info.
     *
     * @param ex
     * @return
     */
    private static String exceptionToString(final Exception ex) {
        try {
            final StringWriter sw = new StringWriter(1024);
            final PrintWriter pw = new PrintWriter(sw);
            ex.printStackTrace(pw);
            pw.close();
            return sw.toString();
        }
        catch (final Exception e) {
            return "";
        }
    }

    private void showResponseFromHoundService(JSONObject response) throws JSONException {

        JSONArray jsonResultsArr = response.getJSONArray("AllResults");
        final ArrayList<String> finalResultList = new ArrayList<String>();
        for(int i=0;i<jsonResultsArr.length();i++){
            JSONObject gg = (JSONObject) jsonResultsArr.get(i);
            finalResultList.add(gg.getString("WrittenResponse"));
        }
        //System.out.println(finalResultList.toString());
        selectedText.setText("Select match text");
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,finalResultList);
        resultList.setAdapter(adapter);
        resultList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                selectedText.setText("You have said "+finalResultList.get(i));
            }
        });
    }

}
