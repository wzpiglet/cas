package org.apereo.cas.web.flow;

import org.apereo.cas.configuration.CasConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.binding.convert.ConversionExecutor;
import org.springframework.binding.convert.service.RuntimeBindingConversionExecutor;
import org.springframework.binding.expression.Expression;
import org.springframework.binding.expression.ExpressionParser;
import org.springframework.binding.expression.ParserContext;
import org.springframework.binding.expression.spel.SpringELExpressionParser;
import org.springframework.binding.expression.support.FluentParserContext;
import org.springframework.binding.expression.support.LiteralExpression;
import org.springframework.binding.mapping.Mapper;
import org.springframework.binding.mapping.impl.DefaultMapper;
import org.springframework.binding.mapping.impl.DefaultMapping;
import org.springframework.context.expression.BeanExpressionContextAccessor;
import org.springframework.context.expression.EnvironmentAccessor;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.ReflectivePropertyAccessor;
import org.springframework.webflow.action.EvaluateAction;
import org.springframework.webflow.action.ViewFactoryActionAdapter;
import org.springframework.webflow.definition.FlowDefinition;
import org.springframework.webflow.definition.registry.FlowDefinitionRegistry;
import org.springframework.webflow.engine.ActionState;
import org.springframework.webflow.engine.DecisionState;
import org.springframework.webflow.engine.EndState;
import org.springframework.webflow.engine.Flow;
import org.springframework.webflow.engine.SubflowAttributeMapper;
import org.springframework.webflow.engine.SubflowState;
import org.springframework.webflow.engine.Transition;
import org.springframework.webflow.engine.TransitionCriteria;
import org.springframework.webflow.engine.TransitionableState;
import org.springframework.webflow.engine.ViewState;
import org.springframework.webflow.engine.WildcardTransitionCriteria;
import org.springframework.webflow.engine.builder.support.FlowBuilderServices;
import org.springframework.webflow.engine.support.DefaultTargetStateResolver;
import org.springframework.webflow.engine.support.DefaultTransitionCriteria;
import org.springframework.webflow.engine.support.GenericSubflowAttributeMapper;
import org.springframework.webflow.execution.Action;
import org.springframework.webflow.execution.ViewFactory;
import org.springframework.webflow.expression.spel.ActionPropertyAccessor;
import org.springframework.webflow.expression.spel.BeanFactoryPropertyAccessor;
import org.springframework.webflow.expression.spel.FlowVariablePropertyAccessor;
import org.springframework.webflow.expression.spel.MapAdaptablePropertyAccessor;
import org.springframework.webflow.expression.spel.MessageSourcePropertyAccessor;
import org.springframework.webflow.expression.spel.ScopeSearchingPropertyAccessor;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@link AbstractCasWebflowConfigurer} is responsible for
 * providing an entry point into the CAS webflow.
 *
 * @author Misagh Moayyed
 * @since 4.2
 */
public abstract class AbstractCasWebflowConfigurer implements CasWebflowConfigurer {

    protected transient Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * The Login flow definition registry.
     */
    protected FlowDefinitionRegistry loginFlowDefinitionRegistry;

    /** CAS Properties. */
    @Autowired
    protected CasConfigurationProperties casProperties;
    
    private FlowBuilderServices flowBuilderServices;
    
    /**
     * Initialize.
     */
    @PostConstruct
    @Override
    public void initialize() {
        try {
            logger.debug("Initializing CAS webflow configuration...");
            if (casProperties.getWebflow().isAutoconfigure()) {
                doInitialize();
            } else {
                logger.warn("Webflow auto-configuration is disabled. CAS will not modify the webflow via {}", getClass().getName());
            }
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * Handle the initialization of the webflow.
     *
     * @throws Exception the exception
     */
    protected abstract void doInitialize() throws Exception;

    /**
     * Gets login flow.
     *
     * @return the login flow
     */
    @Override
    public Flow getLoginFlow() {
        return (Flow) this.loginFlowDefinitionRegistry.getFlowDefinition(FLOW_ID_LOGIN);
    }

    /**
     * Gets start state.
     *
     * @param flow the flow
     * @return the start state
     */
    @Override
    public TransitionableState getStartState(final Flow flow) {
        return TransitionableState.class.cast(flow.getStartState());
    }

    /**
     * Create action state.
     *
     * @param flow    the flow
     * @param name    the name
     * @param actions the actions
     * @return the action state
     */
    @Override
    public ActionState createActionState(final Flow flow, final String name, final Action... actions) {
        if (containsFlowState(flow, name)) {
            logger.debug("Flow {} already contains a definition for state id {}", flow.getId(), name);
            return (ActionState) flow.getTransitionableState(name);
        }
        final ActionState actionState = new ActionState(flow, name);
        logger.debug("Created action state {}", actionState.getId());
        actionState.getActionList().addAll(actions);
        logger.debug("Added action to the action state {} list of actions: {}", actionState.getId(), actionState.getActionList());
        return actionState;
    }

    /**
     * Create decision state decision state.
     *
     * @param flow           the flow
     * @param id             the id
     * @param testExpression the test expression
     * @param thenStateId    the then state id
     * @param elseStateId    the else state id
     * @return the decision state
     */
    @Override
    public DecisionState createDecisionState(final Flow flow, final String id, final String testExpression,
                                             final String thenStateId, final String elseStateId) {
        if (containsFlowState(flow, id)) {
            logger.debug("Flow {} already contains a definition for state id {}", flow.getId(), id);
            return (DecisionState) flow.getTransitionableState(id);
        }

        final DecisionState decisionState = new DecisionState(flow, id);

        final Expression expression = createExpression(flow, testExpression, Boolean.class);
        final Transition thenTransition = createTransition(expression, thenStateId);
        decisionState.getTransitionSet().add(thenTransition);

        final Transition elseTransition = createTransition("*", elseStateId);
        decisionState.getTransitionSet().add(elseTransition);

        return decisionState;

    }

    /**
     * Sets start state.
     *
     * @param flow  the flow
     * @param state the state
     */
    @Override
    public void setStartState(final Flow flow, final String state) {
        flow.setStartState(state);
        final TransitionableState startState = getStartState(flow);
        logger.debug("Start state is now set to {}", startState.getId());
    }

    /**
     * Sets start state.
     *
     * @param flow  the flow
     * @param state the state
     */
    @Override
    public void setStartState(final Flow flow, final TransitionableState state) {
        setStartState(flow, state.getId());
    }
    
    /**
     * From string to class type, based on the flow conversion service.
     *
     * @param targetType the target type
     * @return the conversion executor
     */
    protected ConversionExecutor convertClassToTargetType(final Class targetType) {
        return this.flowBuilderServices.getConversionService().getConversionExecutor(String.class, targetType);
    }

    /**
     * Create evaluate action.
     *
     * @param expression the expression
     * @return the evaluate action
     */
    @Override
    public EvaluateAction createEvaluateAction(final String expression) {
        final ParserContext ctx = new FluentParserContext();
        final Expression action = this.flowBuilderServices.getExpressionParser()
                .parseExpression(expression, ctx);
        final EvaluateAction newAction = new EvaluateAction(action, null);

        logger.debug("Created evaluate action for expression {}", action.getExpressionString());
        return newAction;
    }

    /**
     * Add a default transition to a given state.
     *
     * @param state       the state to include the default transition
     * @param targetState the id of the destination state to which the flow should transfer
     */
    protected void createStateDefaultTransition(final TransitionableState state, final String targetState) {
        if (state == null) {
            logger.debug("Cannot add default transition of [{}] to the given state is null and cannot be found in the flow.", targetState);
            return;
        }
        final Transition transition = createTransition(targetState);
        state.getTransitionSet().add(transition);
    }

    /**
     * Add transition to action state.
     *
     * @param state           the action state
     * @param criteriaOutcome the criteria outcome
     * @param targetState     the target state
     */
    protected void createTransitionForState(final TransitionableState state,
                                            final String criteriaOutcome, final String targetState) {
        try {
            final Transition transition = createTransition(criteriaOutcome, targetState);
            state.getTransitionSet().add(transition);
            logger.debug("Added transition {} to the state {}", transition.getId(), state.getId());
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * Create transition.
     *
     * @param criteriaOutcome the criteria outcome
     * @param targetState     the target state
     * @return the transition
     */
    @Override
    public Transition createTransition(final String criteriaOutcome, final String targetState) {
        return createTransition(new LiteralExpression(criteriaOutcome), targetState);
    }

    /**
     * Create transition.
     *
     * @param criteriaOutcome the criteria outcome
     * @param targetState     the target state
     * @return the transition
     */
    @Override
    public Transition createTransition(final String criteriaOutcome, final TransitionableState targetState) {
        return createTransition(new LiteralExpression(criteriaOutcome), targetState.getId());
    }

    /**
     * Create transition.
     *
     * @param targetState               the target state
     * @param criteriaOutcomeExpression the criteria outcome expression
     * @return the transition
     */
    @Override
    public Transition createTransition(final Expression criteriaOutcomeExpression, final String targetState) {
        final TransitionCriteria criteria;

        if (criteriaOutcomeExpression.toString().equals(WildcardTransitionCriteria.WILDCARD_EVENT_ID)) {
            criteria = WildcardTransitionCriteria.INSTANCE;
        } else {
            criteria = new DefaultTransitionCriteria(criteriaOutcomeExpression);
        }

        final DefaultTargetStateResolver resolver = new DefaultTargetStateResolver(targetState);
        return new Transition(criteria, resolver);
    }

    /**
     * Create expression expression.
     *
     * @param flow         the flow
     * @param expression   the expression
     * @param expectedType the expected type
     * @return the expression
     */
    protected Expression createExpression(final Flow flow, final String expression, final Class expectedType) {
        final ParserContext parserContext = new FluentParserContext()
                .expectResult(expectedType);
        return getSpringExpressionParser().parseExpression(expression, parserContext);
    }

    /**
     * Gets spring expression parser.
     *
     * @return the spring expression parser
     */
    protected SpringELExpressionParser getSpringExpressionParser() {
        final SpelParserConfiguration configuration = new SpelParserConfiguration();
        final SpelExpressionParser spelExpressionParser = new SpelExpressionParser(configuration);
        final SpringELExpressionParser parser = new SpringELExpressionParser(spelExpressionParser,
                this.flowBuilderServices.getConversionService());

        parser.addPropertyAccessor(new ActionPropertyAccessor());
        parser.addPropertyAccessor(new BeanFactoryPropertyAccessor());
        parser.addPropertyAccessor(new FlowVariablePropertyAccessor());
        parser.addPropertyAccessor(new MapAdaptablePropertyAccessor());
        parser.addPropertyAccessor(new MessageSourcePropertyAccessor());
        parser.addPropertyAccessor(new ScopeSearchingPropertyAccessor());
        parser.addPropertyAccessor(new BeanExpressionContextAccessor());
        parser.addPropertyAccessor(new MapAccessor());
        parser.addPropertyAccessor(new MapAdaptablePropertyAccessor());
        parser.addPropertyAccessor(new EnvironmentAccessor());
        parser.addPropertyAccessor(new ReflectivePropertyAccessor());
        return parser;

    }

    /**
     * Create transition without a criteria.
     *
     * @param targetState the target state
     * @return the transition
     */
    @Override
    public Transition createTransition(final String targetState) {
        final DefaultTargetStateResolver resolver = new DefaultTargetStateResolver(targetState);
        return new Transition(resolver);
    }

    /**
     * Add end state backed by view.
     *
     * @param flow   the flow
     * @param id     the id
     * @param viewId the view id
     */
    @Override
    public void createEndState(final Flow flow, final String id, final String viewId) {
        createEndState(flow, id, new LiteralExpression(viewId));
    }

    /**
     * Add end state backed by view.
     *
     * @param flow       the flow
     * @param id         the id
     * @param expression the expression
     */
    @Override
    public void createEndState(final Flow flow, final String id, final Expression expression) {
        final ViewFactory viewFactory = this.flowBuilderServices.getViewFactoryCreator().createViewFactory(
                expression,
                this.flowBuilderServices.getExpressionParser(),
                this.flowBuilderServices.getConversionService(),
                null,
                this.flowBuilderServices.getValidator(),
                this.flowBuilderServices.getValidationHintResolver());

        createEndState(flow, id, viewFactory);
    }

    /**
     * Add end state backed by view.
     *
     * @param flow        the flow
     * @param id          the id
     * @param viewFactory the view factory
     */
    @Override
    public void createEndState(final Flow flow, final String id, final ViewFactory viewFactory) {
        try {
            if (containsFlowState(flow, id)) {
                logger.debug("Flow {} already contains a definition for state id {}", flow.getId(), id);
                return;
            }

            final EndState endState = new EndState(flow, id);
            final Action finalResponseAction = new ViewFactoryActionAdapter(viewFactory);
            endState.setFinalResponseAction(finalResponseAction);
            logger.debug("Created end state state {} on flow id {}, backed by view factory {}", id, flow.getId(), viewFactory);
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * Register flow definition into login flow registry.
     *
     * @param sourceRegistry the source registry
     */
    protected void registerFlowDefinitionIntoLoginFlowRegistry(final FlowDefinitionRegistry sourceRegistry) {
        final String[] flowIds = sourceRegistry.getFlowDefinitionIds();
        for (final String flowId : flowIds) {
            final FlowDefinition definition = sourceRegistry.getFlowDefinition(flowId);
            logger.debug("Registering flow definition [{}]", flowId);
            this.loginFlowDefinitionRegistry.registerFlowDefinition(definition);
        }
    }

    /**
     * Add view state.
     *
     * @param flow       the flow
     * @param id         the id
     * @param expression the expression
     * @return the view state
     */
    @Override
    public ViewState createViewState(final Flow flow, final String id, final Expression expression) {
        try {
            if (containsFlowState(flow, id)) {
                logger.debug("Flow {} already contains a definition for state id {}", flow.getId(), id);
                return (ViewState) flow.getTransitionableState(id);
            }

            final ViewFactory viewFactory = this.flowBuilderServices.getViewFactoryCreator().createViewFactory(
                    expression,
                    this.flowBuilderServices.getExpressionParser(),
                    this.flowBuilderServices.getConversionService(),
                    null,
                    this.flowBuilderServices.getValidator(),
                    this.flowBuilderServices.getValidationHintResolver());

            final ViewState viewState = new ViewState(flow, id, viewFactory);
            logger.debug("Added view state {}", viewState.getId());
            return viewState;
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
        }
        return null;
    }

    /**
     * Add view state.
     *
     * @param flow   the flow
     * @param id     the id
     * @param viewId the view id
     * @return the view state
     */
    @Override
    public ViewState createViewState(final Flow flow, final String id, final String viewId) {
        return createViewState(flow, id, new LiteralExpression(viewId));
    }

    /**
     * Create subflow state.
     *
     * @param flow        the flow
     * @param id          the id
     * @param subflow     the subflow
     * @param entryAction the entry action
     * @return the subflow state
     */
    @Override
    public SubflowState createSubflowState(final Flow flow, final String id, final String subflow,
                                           final Action entryAction) {

        if (containsFlowState(flow, id)) {
            logger.debug("Flow {} already contains a definition for state id {}", flow.getId(), id);
            return (SubflowState) flow.getTransitionableState(id);
        }

        final SubflowState state = new SubflowState(flow, id, new BasicSubflowExpression(subflow,
                this.loginFlowDefinitionRegistry));
        if (entryAction != null) {
            state.getEntryActionList().add(entryAction);
        }

        return state;
    }

    /**
     * Create subflow state subflow state.
     *
     * @param flow    the flow
     * @param id      the id
     * @param subflow the subflow
     * @return the subflow state
     */
    @Override
    public SubflowState createSubflowState(final Flow flow, final String id, final String subflow) {
        return createSubflowState(flow, id, subflow, null);
    }

    /**
     * Create mapper to subflow state.
     *
     * @param mappings the mappings
     * @return the mapper
     */
    protected Mapper createMapperToSubflowState(final List<DefaultMapping> mappings) {
        final DefaultMapper inputMapper = new DefaultMapper();
        mappings.forEach(inputMapper::addMapping);
        return inputMapper;
    }

    /**
     * Create mapping to subflow state.
     *
     * @param name     the name
     * @param value    the value
     * @param required the required
     * @param type     the type
     * @return the default mapping
     */
    protected DefaultMapping createMappingToSubflowState(final String name, final String value,
                                                         final boolean required, final Class type) {

        final ExpressionParser parser = this.flowBuilderServices.getExpressionParser();

        final Expression source = parser.parseExpression(value, new FluentParserContext());
        final Expression target = parser.parseExpression(name, new FluentParserContext());

        final DefaultMapping mapping = new DefaultMapping(source, target);
        mapping.setRequired(required);

        final ConversionExecutor typeConverter =
                new RuntimeBindingConversionExecutor(type, this.flowBuilderServices.getConversionService());
        mapping.setTypeConverter(typeConverter);
        return mapping;
    }

    /**
     * Create subflow attribute mapper.
     *
     * @param inputMapper  the input mapper
     * @param outputMapper the output mapper
     * @return the subflow attribute mapper
     */
    protected SubflowAttributeMapper createSubflowAttributeMapper(final Mapper inputMapper, final Mapper outputMapper) {
        return new GenericSubflowAttributeMapper(inputMapper, outputMapper);
    }

    /**
     * Register multifactor provider authentication webflow.
     *
     * @param flow      the flow
     * @param subflowId the subflow id
     * @param registry  the registry
     */
    protected void registerMultifactorProviderAuthenticationWebflow(final Flow flow, final String subflowId,
                                                                    final FlowDefinitionRegistry registry) {

        final SubflowState subflowState = createSubflowState(flow, subflowId, subflowId);
        final ActionState actionState = (ActionState) flow.getState(CasWebflowConstants.TRANSITION_ID_REAL_SUBMIT);
        final String targetStateId = actionState.getTransition(CasWebflowConstants.TRANSITION_ID_SUCCESS).getTargetStateId();

        final List<DefaultMapping> mappings = new ArrayList<>();
        final Mapper inputMapper = createMapperToSubflowState(mappings);
        final SubflowAttributeMapper subflowMapper = createSubflowAttributeMapper(inputMapper, null);
        subflowState.setAttributeMapper(subflowMapper);
        subflowState.getTransitionSet().add(createTransition(CasWebflowConstants.TRANSITION_ID_SUCCESS, targetStateId));

        logger.debug("Retrieved action state {}", actionState.getId());
        createTransitionForState(actionState, subflowId, subflowId);

        registerFlowDefinitionIntoLoginFlowRegistry(registry);

        final TransitionableState state = flow.getTransitionableState(CasWebflowConstants
                .TRANSITION_ID_INITIAL_AUTHN_REQUEST_VALIDATION_CHECK);
        createTransitionForState(state, subflowId, subflowId);
    }


    public void setLoginFlowDefinitionRegistry(final FlowDefinitionRegistry loginFlowDefinitionRegistry) {
        this.loginFlowDefinitionRegistry = loginFlowDefinitionRegistry;
    }

    public void setFlowBuilderServices(final FlowBuilderServices flowBuilderServices) {
        this.flowBuilderServices = flowBuilderServices;
    }

    /**
     * Contains flow state?
     *
     * @param flow    the flow
     * @param stateId the state id
     * @return true if flow contains the state.
     */
    protected boolean containsFlowState(final Flow flow, final String stateId) {
        return flow.containsState(stateId);
    }
}
