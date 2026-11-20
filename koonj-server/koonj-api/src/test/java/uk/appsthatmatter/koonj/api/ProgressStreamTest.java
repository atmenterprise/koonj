package uk.appsthatmatter.koonj.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProgressStreamTest {
    @Test
    void closesUnwritableStreamAndReclaimsCapacity() throws Exception {
        var scans = mock(ScanController.class);
        when(scans.status(1)).thenReturn(Map.of("state", "RUNNING"));
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var context = mock(AsyncContext.class);
        var output = mock(ServletOutputStream.class);
        when(request.startAsync()).thenReturn(context);
        when(response.getOutputStream()).thenReturn(output);
        when(output.isReady()).thenReturn(false);
        try (var streams = new ProgressStream(scans, mock(InventoryController.class), new ObjectMapper(), 1)) {
            streams.open("scans", 1, request, response);
            var rejected = assertThrows(ResponseStatusException.class,
                    () -> streams.open("scans", 1, request, response));
            assertEquals(429, rejected.getStatusCode().value());
            verify(context, timeout(20_000)).complete();
            verify(scans, times(1)).status(1);
            verify(output, never()).write(any(byte[].class));
            streams.open("scans", 1, request, response);
        }
    }
}
