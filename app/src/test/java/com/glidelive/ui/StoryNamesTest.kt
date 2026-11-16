package com.glidelive.ui

import com.glidelive.princess.Project
import org.junit.Assert.assertEquals
import org.junit.Test

class StoryNamesTest {
    private fun p(title: String) = Project(title, title, emptyList(), emptyList())

    @Test fun firstStoryIsNumberOne() = assertEquals(1, nextStoryNumber(emptyList()))

    @Test fun numberFollowsTheHighestAndSkipsDeletedOnes() {
        assertEquals(4, nextStoryNumber(listOf(p("Mi cuento 1"), p("Mi cuento 3"))))
    }

    @Test fun otherTitlesDoNotCount() = assertEquals(2, nextStoryNumber(listOf(p("Mi cuento 1"), p("El lobo 9"))))
}
