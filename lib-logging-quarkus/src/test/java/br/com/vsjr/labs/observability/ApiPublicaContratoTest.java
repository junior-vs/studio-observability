package br.com.vsjr.labs.observability;

import br.com.vsjr.labs.observability.annotations.Logged;
import br.com.vsjr.labs.observability.annotations.Traced;
import br.com.vsjr.labs.observability.context.enriquecedor.EnriquecedorContexto;
import br.com.vsjr.labs.observability.context.enriquecedor.Priorizavel;
import br.com.vsjr.labs.observability.dsl.Entrypoint;
import br.com.vsjr.labs.observability.dsl.Event;
import br.com.vsjr.labs.observability.dsl.Log;
import br.com.vsjr.labs.observability.dsl.LogEtapas;
import br.com.vsjr.labs.observability.dsl.enums.EntrypointEnum;
import br.com.vsjr.labs.observability.dsl.enums.EventEnum;
import br.com.vsjr.labs.observability.interceptor.LogInterceptor;
import br.com.vsjr.labs.observability.interceptor.TracingInterceptor;
import br.com.vsjr.labs.observability.tracing.enriquecedor.EnriquecedorTracing;
import io.opentelemetry.api.trace.Span;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiPublicaContratoTest {

    @Test
    void log_deve_expor_apenas_registrando_com_event() throws Exception {
        assertTrue(Modifier.isFinal(Log.class.getModifiers()));

        var registrando = Log.class.getMethod("registrando", Event.class);

        assertTrue(Modifier.isStatic(registrando.getModifiers()));
        assertEquals(LogEtapas.EtapaOnde.class, registrando.getReturnType());
        assertThrows(NoSuchMethodException.class, () -> Log.class.getMethod("registrando", String.class));
    }

    @Test
    void dsl_deve_exigir_where_e_restringir_como_a_entrypoint() throws Exception {
        assertTrue(LogEtapas.EtapaOnde.class.isSealed());
        assertTrue(LogEtapas.EtapaOpcional.class.isSealed());
        assertTrue(Arrays.asList(LogEtapas.EtapaOnde.class.getPermittedSubclasses()).contains(Log.class));
        assertTrue(Arrays.asList(LogEtapas.EtapaOpcional.class.getPermittedSubclasses()).contains(Log.class));

        assertEquals(
                LogEtapas.EtapaOpcional.class,
                LogEtapas.EtapaOnde.class.getMethod("em", Class.class, String.class).getReturnType()
        );
        assertEquals(
                LogEtapas.EtapaOpcional.class,
                LogEtapas.EtapaOnde.class.getMethod("aqui").getReturnType()
        );
        assertEquals(
                LogEtapas.EtapaOpcional.class,
                LogEtapas.EtapaOpcional.class.getMethod("como", Entrypoint.class).getReturnType()
        );
        assertThrows(NoSuchMethodException.class, () -> LogEtapas.EtapaOpcional.class.getMethod("como", String.class));
    }

    @Test
    void enums_padrao_devem_implementar_contratos_extensiveis() {
        assertTrue(Event.class.isAssignableFrom(EventEnum.class));
        assertTrue(Entrypoint.class.isAssignableFrom(EntrypointEnum.class));
        assertEquals("evento_generico", EventEnum.EVENTO_GENERICO.getEvent());
        assertEquals("API_REST", EntrypointEnum.API_REST.getEntrypoint());
    }

    @Test
    void annotations_publicas_devem_ser_interceptor_bindings_runtime_para_tipo_e_metodo() {
        validarInterceptorBinding(Logged.class);
        validarInterceptorBinding(Traced.class);

        assertTrue(LogInterceptor.class.isAnnotationPresent(Logged.class));
        assertTrue(TracingInterceptor.class.isAnnotationPresent(Traced.class));
    }

    @Test
    void enriquecedores_devem_manter_contrato_cdi_extensivel_e_priorizado() throws Exception {
        assertTrue(Priorizavel.class.isAssignableFrom(EnriquecedorContexto.class));
        assertTrue(Priorizavel.class.isAssignableFrom(EnriquecedorTracing.class));

        assertEquals(void.class, EnriquecedorContexto.class.getMethod("enriquecer", InvocationContext.class).getReturnType());
        assertEquals(Set.class, EnriquecedorContexto.class.getMethod("chavesMdc").getReturnType());
        assertEquals(void.class, EnriquecedorTracing.class.getMethod("enriquecer", Span.class, InvocationContext.class).getReturnType());
        assertEquals(int.class, Priorizavel.class.getMethod("prioridade").getReturnType());
    }

    private static void validarInterceptorBinding(Class<?> annotationType) {
        assertTrue(annotationType.isAnnotation());
        assertTrue(annotationType.isAnnotationPresent(InterceptorBinding.class));
        assertTrue(annotationType.isAnnotationPresent(Inherited.class));

        var target = annotationType.getAnnotation(Target.class);
        var retention = annotationType.getAnnotation(Retention.class);

        assertEquals(Set.of(ElementType.TYPE, ElementType.METHOD), Set.of(target.value()));
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }
}
