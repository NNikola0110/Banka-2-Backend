package rs.raf.trading.otc.saga.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.ArrayList;
import java.util.List;

@Converter
public class SagaLogEntryListConverter implements AttributeConverter<List<SagaLogEntry>, String> {
    private static final ObjectMapper M = new ObjectMapper();
    @Override public String convertToDatabaseColumn(List<SagaLogEntry> attribute) {
        try { return M.writeValueAsString(attribute == null ? List.of() : attribute); }
        catch (Exception e) { throw new IllegalStateException("SAGA log serialize fail", e); }
    }
    @Override public List<SagaLogEntry> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return new ArrayList<>();
        try { return M.readValue(dbData, new TypeReference<List<SagaLogEntry>>() {}); }
        catch (Exception e) { throw new IllegalStateException("SAGA log deserialize fail", e); }
    }
}
