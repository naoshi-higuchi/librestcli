package org.nopware.librestcli.kvs;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

@RestController
@RequestMapping(path = "/")
public class Controller {
    private final Map<Integer, String> kvs = new HashMap<>();

    @PostMapping(path = "/", produces = "text/plain")
    public ResponseEntity<String> create(@RequestBody String value) {
        int key = kvs.keySet().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(-1) + 1;
        kvs.put(key, value);
        return ResponseEntity.created(URI.create(String.format("/%d", key))).body(value);
    }

    @GetMapping(path = "/{key}", produces = "text/plain")
    public String read(@PathVariable(name = "key") Integer key) {
        String value = kvs.get(key);

        if (value == null) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    String.format("Key %d not found.", key));
        }

        return value;
    }

    @GetMapping(path = "/", produces = "application/json")
    public List<String> readAll() {
        return kvs.keySet().stream()
                .map(Object::toString)
                .toList();
    }

    @PutMapping(path = "/{key}", produces = "text/plain")
    public ResponseEntity<String> update(@PathVariable(name = "key") Integer key, @RequestBody String value) {
        int status = kvs.containsKey(key) ? 200 : 201;
        kvs.put(key, value);
        return ResponseEntity.status(status).body(value);
    }

    @DeleteMapping(path = "/{key}", produces = "text/plain")
    public String delete(@PathVariable(name = "key") Integer key) {
        return kvs.remove(key);
    }

    @DeleteMapping(path = "/")
    public void deleteAll() {
        kvs.clear();
    }
}
