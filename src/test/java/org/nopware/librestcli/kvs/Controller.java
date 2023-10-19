package org.nopware.librestcli.kvs;

import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.HashMap;
import java.util.List;

@RestController
public class Controller {
    private final Map<Integer, String> kvs = new HashMap<>();
    @GetMapping(path = "/", produces = "application/json")
    public List<String> readAll() {
        return kvs.keySet().stream()
                .map(Object::toString)
                .toList();
    }

    @GetMapping(path = "/{key}", produces = "text/plain")
    public String read(@PathVariable(name = "key") Integer key) {
        return kvs.get(key);
    }

    @PutMapping(path = "/{key}", produces = "text/plain")
    public String put(@PathVariable(name = "key") Integer key, @RequestBody String value) {
        kvs.put(key, value);
        return value;
    }

    @PostMapping(path = "/", produces = "text/plain")
    public String post(@RequestBody String value) {
        int key = kvs.keySet().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0) + 1;
        kvs.put(key, value);
        return value;
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
